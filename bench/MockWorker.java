package bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process mock of the BedwarsQol Cloudflare Worker, calibrated to latencies measured
 * against the REAL deployed worker via `wrangler dev --remote` on 2026-06-26:
 *
 *   - client<->colo round trip (RTT) ...... ~40 ms   (measured GET / overhead ~45 ms)
 *   - worker caches.default HIT ............ RTT + ~3 ms
 *   - cold scrape (hypixel cache miss) ..... RTT + ~410 ms  (measured totals 343-585 ms, median ~460)
 *   - hypixel-edge-warm scrape (s-maxage=30) RTT + ~25 ms   (measured Skeppy 2nd fetch 70 ms total)
 *   - burst scraping -> HTTP 429           => a server-side politeness limiter is mandatory
 *
 * Endpoints:
 *   GET /bedwars/{name}?fresh=0|1   single lookup (the current mod path)
 *   GET /batch?names=a,b,c          one call returns many players (the proposed path)
 *
 * Politeness (modelling hypixel's rate limit on the worker's egress IP) is a property of
 * hypixel, NOT of the client, so it is applied identically to both endpoints. The current
 * client's 1300 ms spacing is redundant with this — that redundancy is the inefficiency.
 */
public final class MockWorker {

    // ---- calibrated latency knobs (ms) ----
    final int rttMs;
    final int hitMs;
    final int hypixelWarmMs;
    final double coldMeanMs;
    final double coldStdMs;

    // ---- server-side scrape politeness toward hypixel ----
    private final Semaphore scrapeSlots;     // max concurrent cold scrapes
    private final long minScrapeSpacingMs;   // min gap between scrape STARTS
    private long lastScrapeStartMs = 0;      // guarded by this

    // ---- caches ----
    private final Map<String, Long> workerCache = new ConcurrentHashMap<>(); // name -> expiry (15 min)
    private final Map<String, Long> hypixelEdge = new ConcurrentHashMap<>(); // name -> expiry (30 s)
    private static final long WORKER_TTL_MS = 15 * 60 * 1000L;
    private static final long HYPIXEL_TTL_MS = 30 * 1000L;

    private final Random rng;                 // cold-scrape jitter (seeded -> reproducible)
    private final ExecutorService scrapePool; // for batch fan-out
    private HttpServer server;
    private int port;

    // observability
    final AtomicInteger coldScrapes = new AtomicInteger();
    final AtomicInteger warmScrapes = new AtomicInteger();
    final AtomicInteger cacheHits = new AtomicInteger();

    MockWorker(int rttMs, int hitMs, int hypixelWarmMs, double coldMeanMs, double coldStdMs,
               int scrapeConcurrency, long minScrapeSpacingMs, long seed) {
        this.rttMs = rttMs;
        this.hitMs = hitMs;
        this.hypixelWarmMs = hypixelWarmMs;
        this.coldMeanMs = coldMeanMs;
        this.coldStdMs = coldStdMs;
        this.scrapeSlots = new Semaphore(Math.max(1, scrapeConcurrency), true);
        this.minScrapeSpacingMs = minScrapeSpacingMs;
        this.rng = new Random(seed);
        this.scrapePool = Executors.newFixedThreadPool(64);
    }

    int port() { return port; }

    /** Pre-warm K of the given players into the worker cache (models cross-user / repeat warmth). */
    void prewarm(List<String> players, int warmCount) {
        workerCache.clear();
        hypixelEdge.clear();
        long exp = now() + WORKER_TTL_MS;
        for (int i = 0; i < warmCount && i < players.size(); i++) {
            workerCache.put(players.get(i).toLowerCase(), exp);
        }
    }

    void resetCounters() { coldScrapes.set(0); warmScrapes.set(0); cacheHits.set(0); }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(128));
        server.createContext("/bedwars/", this::handleSingle);
        server.createContext("/batch", this::handleBatch);
        server.start();
        port = server.getAddress().getPort();
    }

    void stop() {
        if (server != null) server.stop(0);
        scrapePool.shutdownNow();
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    private boolean cacheValid(String name) {
        Long exp = workerCache.get(name.toLowerCase());
        return exp != null && now() < exp;
    }

    private synchronized double coldDraw() {
        double v = coldMeanMs + rng.nextGaussian() * coldStdMs;
        return Math.max(300, Math.min(650, v));   // clamp to measured envelope
    }

    private static void sleep(double ms) {
        if (ms <= 0) return;
        try { Thread.sleep((long) ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** One cold/warm scrape through the politeness gate (concurrency + min spacing between starts). */
    private void scrape(String name) {
        try {
            scrapeSlots.acquire();
            try {
                synchronized (this) {
                    long n = now();
                    long wait = (lastScrapeStartMs + minScrapeSpacingMs) - n;
                    if (wait > 0) { sleep(wait); n += wait; }
                    lastScrapeStartMs = n;
                }
                Long hexp = hypixelEdge.get(name.toLowerCase());
                boolean hypWarm = hexp != null && now() < hexp;
                if (hypWarm) { warmScrapes.incrementAndGet(); sleep(hypixelWarmMs); }
                else { coldScrapes.incrementAndGet(); sleep(coldDraw()); }
                long n2 = now();
                hypixelEdge.put(name.toLowerCase(), n2 + HYPIXEL_TTL_MS);
                workerCache.put(name.toLowerCase(), n2 + WORKER_TTL_MS);
            } finally {
                scrapeSlots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleSingle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();      // /bedwars/<name>
        String name = path.substring(path.lastIndexOf('/') + 1);
        boolean fresh = String.valueOf(ex.getRequestURI().getQuery()).contains("fresh=1");

        if (!fresh && cacheValid(name)) { cacheHits.incrementAndGet(); sleep(hitMs); }
        else scrape(name);

        sleep(rttMs);                                     // network round trip
        respond(ex, "{\"success\":true,\"displayName\":\"" + name + "\"}");
    }

    private void handleBatch(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();         // names=a,b,c
        String namesParam = "";
        if (q != null) for (String kv : q.split("&")) if (kv.startsWith("names=")) namesParam = kv.substring(6);
        String[] names = namesParam.isEmpty() ? new String[0] : namesParam.split(",");

        List<Future<?>> futures = new ArrayList<>();
        for (String name : names) {
            if (cacheValid(name)) { cacheHits.incrementAndGet(); /* instant */ }
            else futures.add(scrapePool.submit(() -> scrape(name)));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { /* ignore for bench */ }
        }
        sleep(rttMs);                                     // one round trip for the whole batch
        respond(ex, "{\"success\":true,\"count\":" + names.length + "}");
    }

    private void respond(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}

package bench;

import com.bedwarsqol.stats.RateLimiter;   // the REAL production rate limiter

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BedwarsQol stats-fetch benchmark.
 *
 * Drives three client designs against the calibrated {@link MockWorker}:
 *   CURRENT  - 1 worker thread + the REAL {@link RateLimiter} (1300 ms global spacing), one GET per player.
 *   POOL(W)  - W worker threads, NO client spacing (server politeness binds), one GET per player.
 *   BATCH    - a single GET /batch returning all queued players at once.
 *
 * Metric: makespan = time from "all players enqueued" to "last player resolved" (a.k.a. time-to-all).
 * Also TTFR = time-to-first-resolved. Reported as median + p95 over trials (warmup discarded), with
 * cache warmth and hypixel politeness varied per the research-recommended scenario grid.
 */
public final class Bench {

    static final int RTT = 40, HIT = 3, HYP_WARM = 25;
    static final double COLD_MEAN = 410, COLD_STD = 70;
    static final int TRIALS = 4, WARMUP = 1;          // medians over 3 measured trials (low-variance model)

    // hypixel politeness profiles (property of hypixel, applied to BOTH endpoints)
    static final int[] POLITE_CONC    = {1, 3};
    static final long[] POLITE_SPACING = {1300, 250};
    static final String[] POLITE_NAME = {"conservative(1@1300ms, =today's safe rate)", "moderate(3@250ms, needs 429-backoff)"};

    static List<String> players(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(String.format("Player%02d", i));
        return l;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== BedwarsQol stats-fetch benchmark ===");
        System.out.printf("calibration: RTT=%dms  cacheHit=+%dms  hypixelWarm=+%dms  coldScrape~N(%.0f,%.0f)ms%n",
                RTT, HIT, HYP_WARM, COLD_MEAN, COLD_STD);
        System.out.println("(latencies calibrated to the live worker via `wrangler dev --remote`, 2026-06-26)\n");

        sixteenPlayerGame();
        singlePlayer();

        System.out.println("\n=== done ===");
    }

    // ---------------------------------------------------------------- 16-player game
    static void sixteenPlayerGame() throws Exception {
        System.out.println("################  SCENARIO A: 16-PLAYER GAME (time-to-all-16)  ################\n");
        List<String> all = players(16);

        // CURRENT is independent of warmth/politeness (client 1300ms dominates) -> measure once at 8-warm.
        double[] curMk = new double[TRIALS];
        {
            int[] pol = {0};
            MockWorker w = newWorker(POLITE_CONC[0], POLITE_SPACING[0]);
            w.start();
            for (int t = 0; t < TRIALS; t++) {
                w.prewarm(all, 8); w.resetCounters();
                double[] r = runCurrent(w, taskList(all, 8, -1));
                curMk[t] = max(r);
            }
            w.stop();
        }
        double curMed = median(slice(curMk)), curP95 = p95(slice(curMk));
        System.out.printf("CURRENT (1 thread, 1300ms limiter): makespan median=%.0fms p95=%.0fms  [warmth-invariant]%n%n",
                curMed, curP95);

        System.out.printf("%-10s %-8s | %-22s | %-22s | %-10s%n",
                "politeness", "warmth", "BATCH makespan med/p95", "POOL(4) makespan med/p95", "BATCH speedup");
        System.out.println("-".repeat(92));

        for (int pi = 0; pi < POLITE_NAME.length; pi++) {
            MockWorker w = newWorker(POLITE_CONC[pi], POLITE_SPACING[pi]);
            w.start();
            for (int warm : new int[]{0, 4, 8, 16}) {
                double[] bMk = new double[TRIALS], pMk = new double[TRIALS];
                boolean doPool = (warm == 8);                 // POOL(4) only at the representative cell
                for (int t = 0; t < TRIALS; t++) {
                    w.prewarm(all, warm); w.resetCounters();
                    bMk[t] = max(runBatch(w, all));
                    if (doPool) { w.prewarm(all, warm); w.resetCounters(); pMk[t] = max(runPool(w, taskList(all, warm, -1), 4)); }
                }
                double bMed = median(slice(bMk)), bP95 = p95(slice(bMk));
                String pool = doPool ? String.format("%8.0f / %-9.0f", median(slice(pMk)), p95(slice(pMk))) : "         —";
                System.out.printf("%-10s %2d/16    | %8.0f / %-9.0f | %-22s | %6.1fx%n",
                        pi == 0 ? "conserv." : "moderate", warm, bMed, bP95, pool, curMed / bMed);
            }
            w.stop();
            System.out.println();
        }
        System.out.println("politeness profiles: " + POLITE_NAME[0] + " ; " + POLITE_NAME[1]);
        System.out.println("NOTE: CURRENT makespan is ~constant regardless of warmth because the 1300ms client");
        System.out.println("      limiter serializes all 16 (even pure cache hits). BATCH pays only real cold-scrape work.\n");
    }

    // ---------------------------------------------------------------- single player
    static void singlePlayer() throws Exception {
        System.out.println("################  SCENARIO B: SINGLE PLAYER (time-to-resolve-1)  ################\n");
        List<String> one = players(1);
        List<String> lobby = players(16);

        // Isolated single-player latency is independent of hypixel politeness (only one scrape, no contention).
        MockWorker w = newWorker(POLITE_CONC[1], POLITE_SPACING[1]);
        MockWorker wStream = new MockWorker(RTT, HIT, HYP_WARM, COLD_MEAN * 0.88, COLD_STD * 0.88,
                POLITE_CONC[1], POLITE_SPACING[1], 99);   // streaming early-abort: reads only the ~21% prefix
        w.start(); wStream.start();

        double[] curCold = new double[TRIALS], optCold = new double[TRIALS], strCold = new double[TRIALS];
        double[] curWarm = new double[TRIALS], optWarm = new double[TRIALS];
        for (int t = 0; t < TRIALS; t++) {
            w.prewarm(one, 0); w.resetCounters();             curCold[t] = max(runCurrent(w, taskList(one, 0, -1)));
            w.prewarm(one, 0); w.resetCounters();              optCold[t] = max(runBatch(w, one));
            wStream.prewarm(one, 0); wStream.resetCounters();  strCold[t] = max(runBatch(wStream, one));
            w.prewarm(one, 1); w.resetCounters();             curWarm[t] = max(runCurrent(w, taskList(one, 1, -1)));
            w.prewarm(one, 1); w.resetCounters();              optWarm[t] = max(runBatch(w, one));
        }
        System.out.println("ISOLATED single lookup (no other traffic):");
        row("  cold (cache miss) ", curCold, optCold);
        System.out.printf("    + streaming early-abort: opt median=%.0fms  speedup vs current=%.2fx%n",
                median(slice(strCold)), median(slice(curCold)) / median(slice(strCold)));
        row("  warm (cache hit)  ", curWarm, optWarm);
        w.stop(); wStream.stop();

        // Under contention: hover ONE player while the 16-player lobby fetch is in flight.
        // CURRENT foreground = full priority-queue run, USER item competing for the shared 1300ms limiter.
        // This is politeness-invariant on the client side, so measure once.
        System.out.println("\nUNDER LOBBY CONTENTION (hover 1 player while 16-player fetch is in flight):");
        for (int pi = 0; pi < POLITE_NAME.length; pi++) {
            MockWorker wc = newWorker(POLITE_CONC[pi], POLITE_SPACING[pi]);
            wc.start();
            double[] cur = new double[TRIALS], opt = new double[TRIALS];
            for (int t = 0; t < TRIALS; t++) {
                wc.prewarm(lobby, 0); wc.resetCounters(); cur[t] = runCurrentForeground(wc, lobby);
                wc.prewarm(lobby, 0); wc.resetCounters(); opt[t] = runOptForeground(wc, lobby);
            }
            System.out.printf("  politeness %-10s: current median=%6.0fms   opt median=%6.0fms   speedup=%.2fx%n",
                    pi == 0 ? "conserv." : "moderate", median(slice(cur)), median(slice(opt)),
                    median(slice(cur)) / median(slice(opt)));
            wc.stop();
        }
        System.out.println("  CURRENT: the foreground lookup waits behind background traffic on the shared 1300ms limiter.");
        System.out.println("  OPT: foreground issues its own immediate request, concurrent with the background batch.");
    }

    static void row(String label, double[] cur, double[] opt) {
        double c = median(slice(cur)), o = median(slice(opt));
        System.out.printf("%s: current median=%6.0fms   opt median=%6.0fms   speedup=%.2fx%n", label, c, o, c / o);
    }

    // ---------------------------------------------------------------- client designs
    static final class Task implements Comparable<Task> {
        final int idx; final String name; final int priority; final long seq;
        Task(int idx, String name, int priority, long seq) { this.idx = idx; this.name = name; this.priority = priority; this.seq = seq; }
        public int compareTo(Task o) { return priority != o.priority ? Integer.compare(priority, o.priority) : Long.compare(seq, o.seq); }
    }

    static AtomicLong SEQ = new AtomicLong();

    /** Build N tasks; first `warm` are cache-warm (priority TAB=2). foregroundIdx, if >=0, is USER=0. */
    static List<Task> taskList(List<String> names, int warm, int foregroundIdx) {
        List<Task> l = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int pri = (i == foregroundIdx) ? 0 : 2;
            l.add(new Task(i, names.get(i), pri, SEQ.incrementAndGet()));
        }
        return l;
    }

    /** CURRENT: single worker thread + real RateLimiter, priority queue, one GET per task. */
    static double[] runCurrent(MockWorker w, List<Task> tasks) throws Exception {
        final RateLimiter limiter = new RateLimiter();        // fresh -> lastRequestAt=0
        final PriorityBlockingQueue<Task> q = new PriorityBlockingQueue<>(tasks);
        final double[] resolve = new double[tasks.size()];
        final long t0 = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(tasks.size());
        Thread worker = new Thread(() -> {
            try {
                while (done.getCount() > 0) {
                    Task t = q.poll();
                    if (t == null) break;
                    limiter.awaitSlot();
                    get(w.port(), "/bedwars/" + t.name);
                    resolve[t.idx] = (System.nanoTime() - t0) / 1e6;
                    done.countDown();
                }
            } catch (Exception ignored) {}
        }, "cur-worker");
        worker.setDaemon(true); worker.start();
        done.await();
        return resolve;
    }

    /** POOL(W): W threads, NO client rate limiter; server politeness is the only throttle. */
    static double[] runPool(MockWorker w, List<Task> tasks, int W) throws Exception {
        final PriorityBlockingQueue<Task> q = new PriorityBlockingQueue<>(tasks);
        final double[] resolve = new double[tasks.size()];
        final long t0 = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(tasks.size());
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < W; i++) {
            Thread th = new Thread(() -> {
                Task t;
                while ((t = q.poll()) != null) {
                    get(w.port(), "/bedwars/" + t.name);
                    resolve[t.idx] = (System.nanoTime() - t0) / 1e6;
                    done.countDown();
                }
            }, "pool-" + i);
            th.setDaemon(true); th.start(); ts.add(th);
        }
        done.await();
        return resolve;
    }

    /** BATCH: one GET returns all players at once. */
    static double[] runBatch(MockWorker w, List<String> names) {
        long t0 = System.nanoTime();
        get(w.port(), "/batch?names=" + String.join(",", names));
        double r = (System.nanoTime() - t0) / 1e6;
        double[] res = new double[names.size()];
        Arrays.fill(res, r);
        return res;
    }

    /** CURRENT under contention: 15 background TAB + 1 foreground USER (added last, highest priority). */
    static double runCurrentForeground(MockWorker w, List<String> lobby) throws Exception {
        List<Task> tasks = taskList(lobby, 0, 0);   // idx 0 = USER foreground, rest TAB
        double[] r = runCurrent(w, tasks);
        return r[0];
    }

    /** OPT under contention: background batch for the other 15 + an immediate single GET for the foreground. */
    static double runOptForeground(MockWorker w, List<String> lobby) throws Exception {
        final String fg = lobby.get(0);
        final List<String> bg = lobby.subList(1, lobby.size());
        final long t0 = System.nanoTime();
        final AtomicLong fgResolve = new AtomicLong();
        Thread bgT = new Thread(() -> get(w.port(), "/batch?names=" + String.join(",", bg)), "bg");
        Thread fgT = new Thread(() -> { get(w.port(), "/bedwars/" + fg); fgResolve.set(System.nanoTime() - t0); }, "fg");
        bgT.setDaemon(true); fgT.setDaemon(true);
        bgT.start(); fgT.start();
        fgT.join(); bgT.join();
        return fgResolve.get() / 1e6;
    }

    // ---------------------------------------------------------------- helpers
    static MockWorker newWorker(int conc, long spacing) {
        return new MockWorker(RTT, HIT, HYP_WARM, COLD_MEAN, COLD_STD, conc, spacing, 42);
    }

    static final AtomicInteger ERRORS = new AtomicInteger();
    static void get(int port, String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) { while (r.readLine() != null) {} }
        } catch (Exception e) { ERRORS.incrementAndGet(); }
    }

    static double max(double[] a) { double m = 0; for (double v : a) m = Math.max(m, v); return m; }
    static double[] slice(double[] a) { return Arrays.copyOfRange(a, WARMUP, a.length); }   // drop warmup
    static double median(double[] a) { double[] b = a.clone(); Arrays.sort(b); return b[b.length / 2]; }
    static double p95(double[] a) { double[] b = a.clone(); Arrays.sort(b); return b[(int) Math.ceil(b.length * 0.95) - 1]; }
}

package com.bedwarsqol.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.bedwarsqol.BedwarsQol;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUID-keyed cache and scheduler for Bedwars stats fetched from the BedwarsQol stats
 * backend (Cloudflare Worker / forum scrape). Requests are paced by a shared
 * {@link RateLimiter}, de-duplicated, served from a priority queue (user-initiated >
 * visible players > tab-only), and persisted to disk so repeat encounters cost zero
 * network calls across sessions.
 */
public final class StatsCache {

    /** User typed /bw: fetch immediately. */
    public static final int PRIORITY_USER = 0;
    /** A player you can physically see in the world. */
    public static final int PRIORITY_VISIBLE = 1;
    /** A player only present in the tab list. */
    public static final int PRIORITY_TAB = 2;

    private static final long OK_TTL_MS = 15L * 60L * 1000L;
    private static final long NEGATIVE_TTL_MS = 30L * 60L * 1000L;
    // Short so a transient fetch failure retries within a few seconds (it renders blank meanwhile,
    // never "[?]") instead of sticking for a full minute on a real, lookupable player.
    private static final long ERROR_TTL_MS = 6L * 1000L;
    private static final long FLUSH_INTERVAL_MS = 30L * 1000L;
    private static final int MAX_QUEUE = 512;

    public static final RateLimiter LIMITER = new RateLimiter();

    /** Thrown when a synchronous fetch is throttled by local request pacing. */
    public static final class RateLimitedException extends Exception {
        public RateLimitedException(String message) { super(message); }
    }

    private static final ConcurrentMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final java.util.Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final PriorityBlockingQueue<Task> QUEUE = new PriorityBlockingQueue<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Gson GSON = new GsonBuilder().create();

    private static volatile boolean dirty = false;

    static {
        load();
        Thread worker = new Thread(StatsCache::workerLoop, "BedwarsQol-Stats");
        worker.setDaemon(true);
        worker.start();

        Thread flusher = new Thread(StatsCache::flushLoop, "BedwarsQol-StatsFlush");
        flusher.setDaemon(true);
        flusher.start();

        Runtime.getRuntime().addShutdownHook(new Thread(StatsCache::flush, "BedwarsQol-StatsSave"));
    }

    private StatsCache() {}

    private static boolean useBackend() {
        return statsBackendUrl() != null;
    }

    private static String statsBackendUrl() {
        if (BedwarsQol.config == null) return null;
        String u = BedwarsQol.config.statsBackendUrl;
        if (u == null) return null;
        u = u.trim();
        return u.isEmpty() ? null : u;
    }

    private static String statsBackendToken() {
        return BedwarsQol.config == null ? null : BedwarsQol.config.statsBackendToken;
    }

    public static BedwarsStats getCached(UUID uuid) {
        if (uuid == null) return null;
        return getCachedByKey(uuid.toString());
    }

    /** Stats cached under a bare player name, for players with no resolvable tab-list UUID. */
    public static BedwarsStats getCachedByName(String name) {
        if (name == null || name.isEmpty()) return null;
        return getCachedByKey(nameKey(name));
    }

    private static BedwarsStats getCachedByKey(String k) {
        Entry e = CACHE.get(k);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.timestamp > ttlFor(e.stats.state)) {
            CACHE.remove(k, e);
            return null;
        }
        return e.stats;
    }

    /** Cache key for a name-only lookup, namespaced so it can't collide with a UUID key. */
    private static String nameKey(String name) {
        return "name:" + name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void ensureFetched(UUID uuid, int priority) {
        if (uuid == null) return;
        if (getCached(uuid) != null) return;
        if (!useBackend()) return;
        String playerName = PlayerNames.nameForUuid(uuid);
        if (playerName == null || playerName.isEmpty()) return;
        String k = uuid.toString();
        if (!QUEUED.add(k)) return;
        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUED.remove(k);
            return;
        }
        QUEUE.add(new Task(k, uuid, playerName, priority, SEQ.incrementAndGet()));
    }

    /**
     * Queue a fetch keyed by player name, for players whose UUID isn't in your tab list (nicks, and
     * party/guild members in another lobby). The result is cached under the name, not a UUID.
     */
    public static void ensureFetchedByName(String name, int priority) {
        if (name == null || name.isEmpty()) return;
        if (!useBackend()) return;
        String key = nameKey(name);
        if (getCachedByKey(key) != null) return;
        if (!QUEUED.add(key)) return;
        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUED.remove(key);
            return;
        }
        QUEUE.add(new Task(key, null, name.trim(), priority, SEQ.incrementAndGet()));
    }

    /**
     * Synchronous fetch for the /bw command. May throw on throttle/backend error.
     *
     * @param force when true, bypass both the local cache and the backend's edge cache so the
     *              returned stats are freshly scraped. /bw is invoked rarely enough that the
     *              extra scrape load is negligible; the fresh result is still written back to
     *              the local cache (and refreshes the shared edge entry) for later reuse.
     */
    public static BedwarsStats fetchNow(UUID uuid, String playerName, boolean force) throws Exception {
        if (!force) {
            BedwarsStats cached = getCached(uuid);
            if (cached != null) return cached;
        }

        if (!LIMITER.canRequest()) {
            throw new RateLimitedException(
                    "Rate limited; try again in " + (LIMITER.pauseRemainingMs() / 1000L + 1) + "s");
        }
        LIMITER.awaitSlot();

        String backend = statsBackendUrl();
        if (backend == null) {
            throw new ScraperBackendClient.BackendException("Stats backend not configured");
        }
        String name = playerName != null && !playerName.isEmpty()
                ? playerName
                : PlayerNames.nameForUuid(uuid);
        if (name == null || name.isEmpty()) {
            return BedwarsStats.nicked();
        }
        BedwarsStats stats = ScraperBackendClient.fetch(name, backend, statsBackendToken(), force);
        put(uuid.toString(), stats);
        return stats;
    }

    private static void workerLoop() {
        while (true) {
            Task t;
            try {
                t = QUEUE.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            QUEUED.remove(t.key);

            try {
                if (getCachedByKey(t.key) != null) continue;
                if (!useBackend()) continue;
                if (!LIMITER.canRequest()) continue;
                LIMITER.awaitSlot();

                String backend = statsBackendUrl();
                if (backend == null) continue;
                if (t.playerName == null || t.playerName.isEmpty()) continue;
                put(t.key, ScraperBackendClient.fetch(t.playerName, backend, statsBackendToken(), false));
            } catch (Throwable other) {
                put(t.key, BedwarsStats.error());
            }
        }
    }

    private static void put(String key, BedwarsStats stats) {
        CACHE.put(key, new Entry(stats, System.currentTimeMillis()));
        if (stats.state != BedwarsStats.State.ERROR) dirty = true;
    }

    private static long ttlFor(BedwarsStats.State state) {
        switch (state) {
            case OK: return OK_TTL_MS;
            case ERROR: return ERROR_TTL_MS;
            default: return NEGATIVE_TTL_MS;
        }
    }

    // ---- Disk persistence ------------------------------------------------

    private static File cacheFile() {
        // v2: richer entries (network level, rank, per-mode). Old v1 files are abandoned.
        return new File(configDir(), "bedwarsqol-stats-cache-v2.json");
    }

    /** Mod data dir (~/.bedwarsqol), created on demand. Forge's config dir is unavailable under Weave. */
    private static File configDir() {
        File dir = new File(System.getProperty("user.home"), ".bedwarsqol");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static void flushLoop() {
        while (true) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (dirty) flush();
        }
    }

    private static synchronized void flush() {
        if (!dirty) return;
        dirty = false;
        List<PersistentEntry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Entry> e : CACHE.entrySet()) {
            BedwarsStats s = e.getValue().stats;
            if (s.state == BedwarsStats.State.ERROR) continue;
            if (now - e.getValue().timestamp > ttlFor(s.state)) continue;
            out.add(PersistentEntry.from(e.getKey(), e.getValue()));
        }

        File file = cacheFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(out, w);
        } catch (Exception ignored) {
            dirty = true; // retry on next flush
        }
    }

    private static void load() {
        File file = cacheFile();
        if (!file.isFile()) return;
        Type type = new TypeToken<List<PersistentEntry>>() {}.getType();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<PersistentEntry> in = GSON.fromJson(r, type);
            if (in == null) return;
            long now = System.currentTimeMillis();
            for (PersistentEntry pe : in) {
                if (pe == null || pe.uuid == null) continue;
                BedwarsStats stats = pe.toStats();
                if (stats == null) continue;
                if (now - pe.timestamp > ttlFor(stats.state)) continue;
                CACHE.put(pe.uuid, new Entry(stats, pe.timestamp));
            }
        } catch (Exception ignored) {
            // Corrupt cache file is non-fatal; start empty.
        }
    }

    private static final class Entry {
        final BedwarsStats stats;
        final long timestamp;
        Entry(BedwarsStats stats, long timestamp) {
            this.stats = stats;
            this.timestamp = timestamp;
        }
    }

    private static final class Task implements Comparable<Task> {
        final String key;
        final UUID uuid;
        final String playerName;
        final int priority;
        final long seq;
        Task(String key, UUID uuid, String playerName, int priority, long seq) {
            this.key = key;
            this.uuid = uuid;
            this.playerName = playerName;
            this.priority = priority;
            this.seq = seq;
        }
        @Override
        public int compareTo(Task o) {
            if (priority != o.priority) return Integer.compare(priority, o.priority);
            return Long.compare(seq, o.seq);
        }
    }

    /** Plain DTO for Gson serialization (avoids reflecting over BedwarsStats' final fields). */
    private static final class PersistentEntry {
        String uuid;
        String state;
        String displayName;
        int networkLevel;
        String rankPrefix;
        ModeDTO overall;
        ModeDTO solo;
        ModeDTO doubles;
        ModeDTO threes;
        ModeDTO fours;
        long timestamp;

        static PersistentEntry from(String uuid, Entry e) {
            BedwarsStats s = e.stats;
            PersistentEntry pe = new PersistentEntry();
            pe.uuid = uuid;
            pe.state = s.state.name();
            pe.displayName = s.displayName;
            pe.networkLevel = s.networkLevel;
            pe.rankPrefix = s.rankPrefix;
            pe.overall = ModeDTO.from(s.overall);
            pe.solo = ModeDTO.from(s.solo);
            pe.doubles = ModeDTO.from(s.doubles);
            pe.threes = ModeDTO.from(s.threes);
            pe.fours = ModeDTO.from(s.fours);
            pe.timestamp = e.timestamp;
            return pe;
        }

        BedwarsStats toStats() {
            if (state == null) return null;
            switch (state) {
                case "OK":
                    return BedwarsStats.ok(displayName, networkLevel, rankPrefix,
                            ModeDTO.toOrEmpty(overall), ModeDTO.toOrEmpty(solo), ModeDTO.toOrEmpty(doubles),
                            ModeDTO.toOrEmpty(threes), ModeDTO.toOrEmpty(fours));
                case "NEVER_PLAYED":
                    return BedwarsStats.neverPlayed(displayName);
                case "NICKED":
                    return BedwarsStats.nicked();
                default:
                    return null;
            }
        }
    }

    /** Per-mode counters for serialization. */
    private static final class ModeDTO {
        int finalKills;
        int finalDeaths;
        int wins;
        int losses;
        int kills;
        int deaths;

        static ModeDTO from(BedwarsStats.ModeStats m) {
            ModeDTO d = new ModeDTO();
            d.finalKills = m.finalKills;
            d.finalDeaths = m.finalDeaths;
            d.wins = m.wins;
            d.losses = m.losses;
            d.kills = m.kills;
            d.deaths = m.deaths;
            return d;
        }

        static BedwarsStats.ModeStats toOrEmpty(ModeDTO d) {
            return d == null ? BedwarsStats.ModeStats.EMPTY
                    : new BedwarsStats.ModeStats(d.finalKills, d.finalDeaths, d.wins, d.losses, d.kills, d.deaths);
        }
    }
}

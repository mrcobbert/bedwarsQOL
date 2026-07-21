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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /** Bounded retry gap for an unresolved Urchin lookup on an eligible warm entry. */
    private static final long URCHIN_REFRESH_MS = 60L * 1000L;
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
    /** Bumped by {@link #stripUrchinTags()} (key clear); requests capture it at dispatch so a
     *  pre-clear response is dropped at merge instead of reattaching stripped tags (B2). */
    private static final java.util.concurrent.atomic.AtomicInteger URCHIN_GEN =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Gson GSON = new GsonBuilder().create();

    /** HTTP worker pool. The Worker backend paces its own hypixel scrapes (and backs off on 429),
     *  so the client only needs a few threads to overlap in-flight batches/lookups — NOT the old
     *  single thread + 1300ms global gap that serialized a whole lobby into ~20s. */
    private static final int FETCH_THREADS = 3;
    /** Max players per batch request (a Bedwars lobby is <=16). */
    private static final int MAX_BATCH = 16;
    /** Brief window to let a whole lobby's tab/nametag fetches coalesce into one batch. */
    private static final long COALESCE_WINDOW_MS = 80L;
    private static final ExecutorService FETCHERS = Executors.newFixedThreadPool(FETCH_THREADS, r -> {
        Thread t = new Thread(r, "BedwarsQol-StatsFetch");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean dirty = false;

    static {
        load();
        Thread dispatcher = new Thread(StatsCache::dispatcherLoop, "BedwarsQol-StatsDispatch");
        dispatcher.setDaemon(true);
        dispatcher.start();

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
        Entry e = liveEntry(k);
        return e == null ? null : e.stats;
    }

    /** The non-expired cache entry for a key, evicting it if the TTL has passed. */
    private static Entry liveEntry(String k) {
        Entry e = CACHE.get(k);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.timestamp > ttlFor(e.stats.state)) {
            CACHE.remove(k, e);
            return null;
        }
        return e;
    }

    /** Whether an eligible pair still needs an Urchin lookup: eligible && unresolved && attempt aged out. */
    private static boolean needsUrchinRefresh(boolean pairEligible, Entry e, long now) {
        if (!pairEligible) return false;
        if (e == null) return true;
        return !e.urchinResolved && now - e.urchinAttemptMs > URCHIN_REFRESH_MS;
    }

    /** Undashed lowercase canonical UUID to send, or null when the send-time recheck fails (fail-closed). */
    private static String urchinSendUuid(Task t) {
        if (!t.urchinEligible || t.uuid == null) return null;
        if (!EligibilitySnapshot.current().eligible(t.playerName, t.uuid)) return null;
        return t.uuid.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Reset Urchin resolution on all entries so warm players re-enqueue (used on a successful key set). */
    public static void invalidateUrchinResolution() {
        // Advance the generation on SET too: an unavailable result from a request sent
        // while the key was missing/rejected must not re-resolve the entry after the new
        // key succeeds (it would pin the player resolved-empty for the entry lifetime).
        URCHIN_GEN.incrementAndGet();
        for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
            Entry cur = e.getValue();
            CACHE.put(e.getKey(), new Entry(cur.stats, cur.timestamp, false, 0L));
        }
    }

    /** Immediately drop every entry's tags and mark it resolved-empty (used on a successful key clear). */
    public static void stripUrchinTags() {
        // Bump first: any request already in flight now carries an older generation and its Urchin
        // resolution is dropped at merge (mergeUrchin/putResolved), so it cannot reattach tags here.
        URCHIN_GEN.incrementAndGet();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
            Entry cur = e.getValue();
            CACHE.put(e.getKey(), new Entry(cur.stats.withUrchinTags(null), cur.timestamp, true, now));
        }
    }

    /** Cache key for a name-only lookup, namespaced so it can't collide with a UUID key. */
    private static String nameKey(String name) {
        return "name:" + name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void ensureFetched(UUID uuid, int priority) {
        ensureFetched(uuid, priority, false);
    }

    /**
     * As {@link #ensureFetched(UUID, int)} but marks the task Urchin-eligible when {@code urchinEligible}
     * is true (only ever set from a current-session confirmed-row pair). An eligible task re-enqueues
     * even on a cache hit while its Urchin lookup is unresolved and the 60 s retry gap has passed.
     */
    public static void ensureFetched(UUID uuid, int priority, boolean urchinEligible) {
        if (uuid == null) return;
        if (!useBackend()) return;
        String k = uuid.toString();
        Entry e = liveEntry(k);
        long now = System.currentTimeMillis();
        if (e != null && !needsUrchinRefresh(urchinEligible, e, now)) return;
        String playerName = PlayerNames.nameForUuid(uuid);
        if (playerName == null || playerName.isEmpty()) return;
        if (!QUEUED.add(k)) return;
        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUED.remove(k);
            return;
        }
        QUEUE.add(new Task(k, uuid, playerName, priority, SEQ.incrementAndGet(), urchinEligible));
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
        QUEUE.add(new Task(key, null, name.trim(), priority, SEQ.incrementAndGet(), false));
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

    /**
     * Drains the priority queue and dispatches work to {@link #FETCHERS}. A USER-priority task (a /bw
     * or chat-hover lookup the player is waiting on) is fetched immediately as a single request; lower
     * priority tasks (visible players, tab-only) are coalesced over a short window into one batch
     * request, so a whole 16-player lobby resolves in one round trip instead of 16 serialized ones.
     */
    /**
     * Drains the priority queue and dispatches work to {@link #FETCHERS}. A USER-priority task (a /bw
     * or chat-hover lookup the player is waiting on) is fetched immediately as a single request; lower
     * priority tasks (visible players, tab-only) are coalesced over a short window into one streaming
     * batch request, so a whole lobby resolves over one round trip and renders progressively.
     *
     * <p><b>De-dup invariant:</b> a key stays in {@link #QUEUED} from enqueue until its result lands
     * (cleared by {@link #submitSingle}/{@link #submitBatch}, NOT here at dequeue). Otherwise a player
     * being scraped — not yet in CACHE — would be re-enqueued every render frame, firing redundant
     * scrapes that trip hypixel's ~1.7/s per-IP rate limit and slow the whole lobby down.
     */
    private static void dispatcherLoop() {
        while (true) {
            Task first;
            try {
                first = QUEUE.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (first.priority == PRIORITY_USER) {
                submitSingle(first);
                continue;
            }

            // Background: let the rest of the lobby enqueue, then grab the whole batch at once.
            List<Task> drained = new ArrayList<>();
            drained.add(first);
            try {
                Thread.sleep(COALESCE_WINDOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            QUEUE.drainTo(drained, MAX_BATCH - 1);

            // Keep the priority lane crisp: any USER tasks swept in still go out immediately.
            List<Task> batch = new ArrayList<>();
            for (Task t : drained) {
                if (t.priority == PRIORITY_USER) submitSingle(t);
                else batch.add(t);
            }
            if (!batch.isEmpty()) submitBatch(batch);
        }
    }

    private static void submitSingle(Task t) {
        final int gen = URCHIN_GEN.get(); // capture at dispatch; a later clear drops this result's tags
        FETCHERS.submit(() -> {
            long now = System.currentTimeMillis();
            try {
                Entry e = liveEntry(t.key);
                if (e != null && !needsUrchinRefresh(t.urchinEligible, e, now)) return;
                String backend = statsBackendUrl();
                if (backend == null || t.playerName == null || t.playerName.isEmpty()) return;
                String sendUuid = urchinSendUuid(t); // null unless send-time recheck passes
                UrchinResult[] holder = {null};
                BedwarsStats s = ScraperBackendClient.fetch(t.playerName, backend, statsBackendToken(),
                        false, sendUuid, r -> holder[0] = r);
                putResolved(t.key, s, holder[0], sendUuid != null, now, gen);
            } catch (Throwable other) {
                put(t.key, BedwarsStats.error());
            } finally {
                QUEUED.remove(t.key); // done (or skipped) -> allow a future re-fetch
            }
        });
    }

    /**
     * Streams a batch: each player is cached and un-marked from {@link #QUEUED} the moment its NDJSON
     * line arrives, so the HUD fills in progressively rather than waiting for the slowest scrape.
     * Names that never resolve (network error) are marked ERROR (short TTL) so they retry soon.
     */
    private static void submitBatch(List<Task> batch) {
        final int gen = URCHIN_GEN.get(); // capture at dispatch; a later clear drops these results' tags
        FETCHERS.submit(() -> {
            String backend = statsBackendUrl();
            long now = System.currentTimeMillis();
            // Map requested name -> task(s) (a name may back multiple keys); track who resolves.
            Map<String, List<Task>> byName = new HashMap<>();
            Set<String> names = new LinkedHashSet<>();
            for (Task t : batch) {
                Entry e = liveEntry(t.key);
                boolean keep = e == null || needsUrchinRefresh(t.urchinEligible, e, now);
                if (t.playerName == null || t.playerName.isEmpty() || !keep) {
                    QUEUED.remove(t.key);
                    continue;
                }
                byName.computeIfAbsent(t.playerName, k -> new ArrayList<>()).add(t);
                names.add(t.playerName);
            }
            if (backend == null || names.isEmpty()) {
                for (List<Task> ts : byName.values()) for (Task t : ts) QUEUED.remove(t.key);
                return;
            }
            // Index-aligned uuids param: the eligible member's canonical UUID per name (send-time
            // rechecked), "-" for ineligible members. Header only sent when at least one member passes.
            List<String> nameList = new ArrayList<>(names);
            StringBuilder uuidsSb = new StringBuilder();
            boolean anyEligible = false;
            // The single cache key per name whose UUID is actually emitted in this request. Its attempt
            // is stamped when the base stats line lands so a no-Urchin-metadata batch still bounds the
            // retry to 60 s (B1); Urchin metadata merges ONLY into it, never into other keys sharing the
            // name (I6). A name absent from the map had no eligible member.
            Map<String, String> emittedKeyByName = new HashMap<>();
            // Canonical emitted UUID -> cache key. The Urchin resolution merges by UUID identity, never
            // by name (B1): a case-varied duplicate name could otherwise route one UUID's accusation to
            // another's cache key. Names are lowercased in emittedKeyByName for defense in depth.
            Map<String, String> emittedKeyByUuid = new HashMap<>();
            for (String nm : nameList) {
                String send = "-";
                for (Task t : byName.get(nm)) {
                    String u = urchinSendUuid(t);
                    if (u != null) {
                        send = u;
                        anyEligible = true;
                        emittedKeyByName.put(nm.toLowerCase(java.util.Locale.ROOT), t.key);
                        emittedKeyByUuid.put(u, t.key);
                        break;
                    }
                }
                if (uuidsSb.length() > 0) uuidsSb.append(',');
                uuidsSb.append(send);
            }
            String uuidsCsv = anyEligible ? uuidsSb.toString() : null;
            Set<String> resolved = new HashSet<>();
            try {
                ScraperBackendClient.fetchBatchStreaming(nameList, backend, statsBackendToken(),
                        (name, stats) -> {
                            List<Task> ts = byName.get(name);
                            if (ts == null) return;
                            resolved.add(name);
                            String emittedKey = emittedKeyByName.get(name.toLowerCase(java.util.Locale.ROOT));
                            for (Task t : ts) {
                                // Stamp the attempt only for the emitted UUID's key even if no Urchin
                                // line follows (B1); other keys sharing the name are not the target (I6).
                                putBase(t.key, stats,
                                        UrchinRefreshPolicy.isUrchinMergeTarget(t.key, emittedKey), now);
                                QUEUED.remove(t.key); // clear in-flight as each player lands
                            }
                        },
                        (name, level) -> {
                            // Star arrives after the counters — upgrade the cached entry in place.
                            List<Task> ts = byName.get(name);
                            if (ts == null) return;
                            for (Task t : ts) {
                                Entry e = CACHE.get(t.key);
                                if (e != null) put(t.key, e.stats.withLevel(level));
                            }
                        },
                        uuidsCsv,
                        (name, result) -> {
                            // Merge the resolution into ONLY the cache key whose EMITTED canonical UUID
                            // equals the result's UUID (B1). Selecting by UUID, not by the streamed name,
                            // stops a case-varied duplicate name from cross-attaching one UUID's accusation
                            // to another; a null/unmatched result UUID drops the resolution everywhere.
                            String emittedKey = UrchinRefreshPolicy.urchinMergeTarget(
                                    result == null ? null : result.uuid, emittedKeyByUuid);
                            if (emittedKey == null) return;
                            mergeUrchin(emittedKey, result, System.currentTimeMillis(), gen);
                        });
            } catch (Throwable other) {
                // unresolved tasks fall back to single lookups below
            } finally {
                // Any player the batch didn't resolve falls back to a per-player lookup — the
                // long-standing single path — instead of leaving the tab/nametag blank. This also
                // covers a deployed Worker that predates the /bedwars/batch route: its single-route
                // fallback returns one non-NDJSON object the stream can't map, so nothing resolves and
                // everyone lands here. Once the batch Worker is deployed this branch goes quiet.
                for (Map.Entry<String, List<Task>> e : byName.entrySet()) {
                    if (resolved.contains(e.getKey())) continue;
                    for (Task t : e.getValue()) {
                        Entry cur = liveEntry(t.key);
                        if (cur == null || needsUrchinRefresh(t.urchinEligible, cur, now)) {
                            try {
                                String sendUuid = urchinSendUuid(t); // batch-fallback single: recheck too
                                UrchinResult[] holder = {null};
                                BedwarsStats s = ScraperBackendClient.fetch(t.playerName, backend,
                                        statsBackendToken(), false, sendUuid, r -> holder[0] = r);
                                putResolved(t.key, s, holder[0], sendUuid != null, System.currentTimeMillis(), gen);
                            } catch (Throwable single) {
                                if (cur == null) put(t.key, BedwarsStats.error());
                            }
                        }
                        QUEUED.remove(t.key);
                    }
                }
            }
        });
    }

    private static void put(String key, BedwarsStats stats) {
        putBase(key, stats, false, 0L);
    }

    /**
     * Base (non-Urchin) stats merge. When {@code uuidEmitted} is true this member's canonical UUID
     * was actually sent in the request, so its Urchin attempt is stamped to {@code now} even if no
     * resolution line follows (B1) - without marking the entry resolved. See
     * {@link UrchinRefreshPolicy#attemptForBaseMerge}.
     */
    private static void putBase(String key, BedwarsStats stats, boolean uuidEmitted, long now) {
        Entry prior = CACHE.get(key);
        BedwarsStats merged = stats;
        boolean resolved = false;
        long attempt = 0L;
        if (prior != null) {
            resolved = prior.urchinResolved;
            attempt = prior.urchinAttemptMs;
            // A plain stats refresh (star update, re-fetch) keeps any tags already resolved for this key.
            if (stats.urchinTags.isEmpty() && !prior.stats.urchinTags.isEmpty()) {
                merged = stats.withUrchinTags(prior.stats.urchinTags);
            }
        }
        attempt = UrchinRefreshPolicy.attemptForBaseMerge(attempt, uuidEmitted, now);
        CACHE.put(key, new Entry(merged, System.currentTimeMillis(), resolved, attempt));
        if (stats.state != BedwarsStats.State.ERROR) dirty = true;
    }

    /** Put fresh stats plus the Urchin resolution from the same fetch (single / batch-fallback paths). */
    private static void putResolved(String key, BedwarsStats stats, UrchinResult result,
                                    boolean attempted, long now, int reqGen) {
        // Key cleared mid-flight: merge the stats but drop the Urchin resolution so a pre-clear
        // response can't reattach stripped tags (B2). Prior tags are already empty post-strip.
        if (UrchinRefreshPolicy.dropStaleUrchin(reqGen, URCHIN_GEN.get())) result = null;
        Entry prior = CACHE.get(key);
        BedwarsStats merged = stats;
        boolean resolved = false;
        long attempt = attempted ? now : (prior != null ? prior.urchinAttemptMs : 0L);
        if (result != null) {
            merged = stats.withUrchinTags(result.tags);
            resolved = result.resolved();
        } else if (prior != null) {
            resolved = prior.urchinResolved;
            if (!prior.stats.urchinTags.isEmpty()) merged = stats.withUrchinTags(prior.stats.urchinTags);
        }
        CACHE.put(key, new Entry(merged, now, resolved, attempt));
        if (stats.state != BedwarsStats.State.ERROR) dirty = true;
    }

    /** Merge a follow-up / inline Urchin resolution into an existing entry (tags are not persisted). */
    private static void mergeUrchin(String key, UrchinResult result, long now, int reqGen) {
        Entry prior = CACHE.get(key);
        if (prior == null || result == null) return; // documented drop: next fetch attaches inline
        // Key cleared after this request dispatched: drop the tags rather than reattach them (B2).
        if (UrchinRefreshPolicy.dropStaleUrchin(reqGen, URCHIN_GEN.get())) return;
        BedwarsStats s = prior.stats.withUrchinTags(result.tags);
        boolean resolved = result.resolved() || prior.urchinResolved;
        CACHE.put(key, new Entry(s, prior.timestamp, resolved, now));
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
        return new File(configDir(), "cobblify-stats-cache-v2.json");
    }

    /** Mod data dir (~/.cobblify), created on demand. Forge's config dir is unavailable under Weave. */
    private static File configDir() {
        File dir = new File(System.getProperty("user.home"), ".cobblify");
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
        /** Urchin lookup concluded (checked/unavailable) for this entry — no further Urchin retry. */
        final boolean urchinResolved;
        /** When the last Urchin lookup was attempted (bounds the 60 s refresh predicate). */
        final long urchinAttemptMs;
        Entry(BedwarsStats stats, long timestamp) {
            this(stats, timestamp, false, 0L);
        }
        Entry(BedwarsStats stats, long timestamp, boolean urchinResolved, long urchinAttemptMs) {
            this.stats = stats;
            this.timestamp = timestamp;
            this.urchinResolved = urchinResolved;
            this.urchinAttemptMs = urchinAttemptMs;
        }
    }

    private static final class Task implements Comparable<Task> {
        final String key;
        final UUID uuid;
        final String playerName;
        final int priority;
        final long seq;
        /** Provenance: the task was created from a current-session confirmed-row pair (send-time rechecked). */
        final boolean urchinEligible;
        Task(String key, UUID uuid, String playerName, int priority, long seq, boolean urchinEligible) {
            this.key = key;
            this.uuid = uuid;
            this.playerName = playerName;
            this.priority = priority;
            this.seq = seq;
            this.urchinEligible = urchinEligible;
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
        int bedwarsLevel;
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
            pe.bedwarsLevel = s.bedwarsLevel;
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
                    return BedwarsStats.ok(displayName, networkLevel, bedwarsLevel, rankPrefix,
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

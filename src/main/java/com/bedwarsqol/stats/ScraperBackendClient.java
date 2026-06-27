package com.bedwarsqol.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

/**
 * Fetches Bedwars stats from the BedwarsQol Cloudflare Worker (forum scrape backend).
 * No Hypixel API key; Hypixel only sees Cloudflare egress, not the player's home IP.
 *
 * <p>Two shapes: {@link #fetch} for a single player (the immediate USER path), and
 * {@link #fetchBatch} for many players in one request (the background-lobby path). The Worker
 * paces its own cache-miss scrapes, so the client no longer has to serialize requests itself.
 */
public final class ScraperBackendClient {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    // A batch may scrape several cold pages server-side; give it more room than a single lookup.
    private static final int BATCH_READ_TIMEOUT_MS = 30000;

    private ScraperBackendClient() {}

    public static final class BackendException extends IOException {
        public BackendException(String message) { super(message); }
    }

    public static BedwarsStats fetch(String playerName, String baseUrl, String token, boolean fresh) throws IOException {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new BackendException("Empty player name");
        }
        String base = normalizeBase(baseUrl);
        if (base == null) throw new BackendException("No stats backend URL configured");

        String encoded = java.net.URLEncoder.encode(playerName.trim(), "UTF-8");
        // fresh=1 tells the Worker to bypass (and refresh) its edge cache for an up-to-date scrape.
        String url = base + "/bedwars/" + encoded + (fresh ? "?fresh=1" : "");
        HttpURLConnection conn = open(url, token, READ_TIMEOUT_MS);

        String body = readBody(conn);
        return statsFromPlayerObject(parseJsonObject(body), playerName.trim());
    }

    /**
     * Resolve many players in one streaming request. The backend returns NDJSON — one player object
     * per line (each carrying a {@code "name"} field), cache hits first then cold scrapes as they land
     * — and this invokes {@code onResult} for each line the moment it arrives, so callers can render
     * players progressively instead of waiting for the slowest (rate-limited) scrape in the lobby.
     */
    public static void fetchBatchStreaming(List<String> names, String baseUrl, String token,
            BiConsumer<String, BedwarsStats> onResult) throws IOException {
        fetchBatchStreaming(names, baseUrl, token, onResult, null);
    }

    /**
     * As {@link #fetchBatchStreaming(List, String, String, BiConsumer)}, but also receives the Bedwars
     * star as a follow-up. The backend streams counters first, then a lightweight {@code starUpdate}
     * line per player once its achievements page (the only source of the real star) resolves; those
     * arrive on {@code onStar} so the caller can upgrade the level in place without a second request.
     */
    public static void fetchBatchStreaming(List<String> names, String baseUrl, String token,
            BiConsumer<String, BedwarsStats> onResult, ObjIntConsumer<String> onStar) throws IOException {
        if (names == null || names.isEmpty()) return;
        String base = normalizeBase(baseUrl);
        if (base == null) throw new BackendException("No stats backend URL configured");

        StringBuilder param = new StringBuilder();
        for (String n : names) {
            if (n == null) continue;
            String t = n.trim();
            if (t.isEmpty()) continue;
            if (param.length() > 0) param.append(',');
            param.append(java.net.URLEncoder.encode(t, "UTF-8"));
        }
        if (param.length() == 0) return;

        String url = base + "/bedwars/batch?names=" + param;
        HttpURLConnection conn = open(url, token, BATCH_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/x-ndjson");
        conn.setRequestProperty("Accept-Encoding", "identity"); // keep NDJSON lines unbuffered

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (code < 200 || code >= 300) {
            readAll(in);
            throw new BackendException("Stats backend returned " + code);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                JsonObject po;
                try {
                    po = parseJsonObject(line);
                } catch (IOException e) {
                    continue; // skip a malformed line, keep streaming
                }
                String name = string(po, "name");
                if (name == null || name.isEmpty()) continue;
                if (bool(po, "starUpdate", false)) {
                    int level = number(po, "bedwarsLevel");
                    if (onStar != null && level > 0) onStar.accept(name, level);
                    continue;
                }
                onResult.accept(name, statsFromPlayerObject(po, name));
            }
        }
    }

    private static HttpURLConnection open(String url, String token, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "BedwarsQol/0.1 ScraperBackendClient");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("X-BedwarsQol-Token", token.trim());
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        Tls.apply(conn);
        return conn;
    }

    /** Reads the full body (draining error streams too, so the keep-alive socket can be reused). */
    private static String readBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        if (code < 200 || code >= 300) {
            throw new BackendException("Stats backend returned " + code);
        }
        return body;
    }

    private static String normalizeBase(String baseUrl) {
        if (baseUrl == null) return null;
        String s = baseUrl.trim();
        if (s.isEmpty()) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static JsonObject parseJsonObject(String body) throws IOException {
        try {
            JsonElement el = new JsonParser().parse(body == null ? "" : body);
            if (el == null || !el.isJsonObject()) throw new IOException("bad json");
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid backend response", e);
        }
    }

    /** Build a {@link BedwarsStats} from one player object (the single body, or one entry of a batch). */
    private static BedwarsStats statsFromPlayerObject(JsonObject root, String requestedName) {
        if (!bool(root, "success", true)) {
            String state = string(root, "state");
            if ("NICKED".equals(state)) return BedwarsStats.nicked();
            return BedwarsStats.error();
        }

        String state = string(root, "state");
        String displayName = string(root, "displayName");
        if (displayName == null || displayName.isEmpty()) displayName = requestedName;

        if ("NEVER_PLAYED".equals(state)) {
            return BedwarsStats.neverPlayed(displayName);
        }
        if ("NICKED".equals(state)) {
            return BedwarsStats.nicked();
        }
        if (state != null && !"OK".equals(state)) {
            return BedwarsStats.error();
        }

        int networkLevel = number(root, "networkLevel");
        int bedwarsLevel = number(root, "bedwarsLevel");
        String rankPrefix = HypixelRanks.prefix(string(root, "rank"));
        JsonObject modes = obj(root, "modes");
        return BedwarsStats.ok(
                displayName,
                networkLevel,
                bedwarsLevel,
                rankPrefix,
                readOverall(root),
                readMode(modes, "solo"),
                readMode(modes, "doubles"),
                readMode(modes, "threes"),
                readMode(modes, "fours"));
    }

    private static JsonObject obj(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return null;
        JsonElement el = root.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static BedwarsStats.ModeStats readMode(JsonObject modes, String key) {
        JsonObject o = obj(modes, key);
        return o == null ? BedwarsStats.ModeStats.EMPTY : modeFrom(o);
    }

    /** Overall stats from the {@code overall} object, falling back to flat top-level fields. */
    private static BedwarsStats.ModeStats readOverall(JsonObject root) {
        JsonObject o = obj(root, "overall");
        return modeFrom(o != null ? o : root);
    }

    private static BedwarsStats.ModeStats modeFrom(JsonObject o) {
        return new BedwarsStats.ModeStats(
                number(o, "finalKills"),
                number(o, "finalDeaths"),
                number(o, "wins"),
                number(o, "losses"),
                number(o, "kills"),
                number(o, "deaths"));
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        if (root == null || key == null || !root.has(key)) return fallback;
        try {
            return root.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String string(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return null;
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int number(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return 0;
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}

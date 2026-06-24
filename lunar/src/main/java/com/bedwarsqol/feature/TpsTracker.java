package com.bedwarsqol.feature;

/**
 * Estimates server TPS on the client. The client can't read TPS directly, but the server sends a
 * time-update packet every 20 server ticks; by timing the real interval between those packets we
 * derive ticks-per-second (≈20 when healthy, lower when the server is lagging). Fed by
 * {@code NetHandlerPlayClientMixin}. Works in singleplayer too (the integrated server sends them).
 */
public final class TpsTracker {

    private static final double TICKS_PER_UPDATE = 20.0;
    /** A gap longer than this is a pause/reconnect, not lag — don't let it skew the reading. */
    private static final double GAP_SECONDS = 5.0;

    private static volatile long lastNanos = 0L;
    private static volatile double tps = 20.0;
    private static volatile boolean hasData = false;

    private TpsTracker() {}

    /** Called once per server time-update packet (~every 20 server ticks). */
    public static void onTimeUpdate() {
        long now = System.nanoTime();
        long last = lastNanos;
        lastNanos = now;
        if (last == 0L) return; // first packet: just set the baseline

        double seconds = (now - last) / 1_000_000_000.0;
        if (seconds <= 0.0 || seconds > GAP_SECONDS) return; // ignore pauses/reconnects

        double sample = TICKS_PER_UPDATE / seconds;
        if (sample > 20.0) sample = 20.0;
        if (sample < 0.0) sample = 0.0;
        // Exponential moving average to smooth normal jitter.
        tps = hasData ? tps * 0.7 + sample * 0.3 : sample;
        hasData = true;
    }

    public static double getTps() {
        return tps;
    }

    public static boolean hasData() {
        return hasData;
    }
}

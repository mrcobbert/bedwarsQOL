package com.bedwarsqol.anticheat;

/**
 * A violation level for one check on one player. Checks {@link #add} evidence as it arrives (negative
 * amounts reward clean behaviour), {@link #tick} applies the passive per-tick decay, and the detector
 * flags when {@link #tripped}. The floor is 0 so long clean streaks can't bank "credit" that later
 * masks real violations.
 */
final class Vl {

    private final int decayPerTick;
    private final int threshold;
    private int value;

    Vl(int decayPerTick, int threshold) {
        this.decayPerTick = decayPerTick;
        this.threshold = threshold;
    }

    void add(int amount) {
        value = Math.max(0, value + amount);
    }

    void tick() {
        if (decayPerTick > 0 && value > 0) value = Math.max(0, value - decayPerTick);
    }

    boolean tripped() {
        return value >= threshold;
    }

    int value() {
        return value;
    }

    void reset() {
        value = 0;
    }
}

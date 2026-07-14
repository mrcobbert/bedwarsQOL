package com.bedwarsqol.anticheat;

/**
 * Everything the detector has observed about one other player, all of it reconstructed from inbound
 * server packets — never from the interpolated {@code EntityOtherPlayerMP} render state, which trails
 * the last packet by a 3-tick lerp. Positions are sampled once per client tick from the entity's
 * {@code serverPos*} fields (the raw tracker values, 1/32-block fixed point); rotations and state
 * flags come straight from the packet hooks in {@code NetHandlerPlayClientMixin}.
 */
final class PlayerData {

    /** Ticks of position history kept — the through-wall check rewinds the victim up to 9 ticks (~450ms)
     *  to grant the attacker lag compensation, matching MWE's {@code MAX_TICK_DELAY}. */
    static final int POS_HISTORY = 10;

    // Once-per-tick server position samples, newest at `head`.
    private final double[] px = new double[POS_HISTORY];
    private final double[] py = new double[POS_HISTORY];
    private final double[] pz = new double[POS_HISTORY];
    private int head = -1;
    int posSamples;

    void pushPos(double x, double y, double z) {
        head = (head + 1) % POS_HISTORY;
        px[head] = x;
        py[head] = y;
        pz[head] = z;
        if (posSamples < POS_HISTORY) posSamples++;
    }

    double x(int ticksAgo) { return px[idx(ticksAgo)]; }
    double y(int ticksAgo) { return py[idx(ticksAgo)]; }
    double z(int ticksAgo) { return pz[idx(ticksAgo)]; }

    private int idx(int ticksAgo) {
        return (head - ticksAgo + 2 * POS_HISTORY) % POS_HISTORY;
    }

    // Latest server rotations in degrees, from the raw packet bytes (1.40625° quantum). Head yaw is the
    // freshest look signal the protocol gives observers; pitch only arrives on Entity Look packets.
    float yawHead = Float.NaN;
    float yaw = Float.NaN;
    float pitch = Float.NaN;

    // Raw metadata flags byte 0 state (bit 1 sneak, bit 3 sprint, bit 4 using-item). Tracked from the
    // metadata packets ourselves so the autoblock equipment-flicker bypass (which only breaks the
    // client-side ItemInUse pose, not the flag) can't hide the blocking state from us.
    boolean usingItem;
    boolean sprinting;
    boolean sneaking;
    /** Consecutive ticks the using-item flag has been set (0 while clear). */
    int useTicks;
    /** Consecutive ticks the sprint flag has been set (0 while clear). */
    int sprintTicks;

    int lastSwingTick = -100;
    int lastHurtTick = -100;
    /** Last tick a hurt/crit on this player was attributed to an attacker — dedupes the double
     *  correlation when both the hurt status and the crit particles arrive for one hit. A short
     *  refractory (not strict tick-equality) covers the packets straddling a tick boundary. */
    int lastAttributedTick = -1000;
    /** Entity {@code ticksExisted} at the last data touch. A regression means this entity id was reused
     *  or the player re-entered tracking range, so the buffered position history and metadata flags are
     *  stale and must be dropped (else a "stuck blocking / stuck sprinting" flag accrues on an innocent). */
    int lastTicksExisted = Integer.MAX_VALUE;
    /** Tick of the last >4-block jump between consecutive position samples: a teleport/respawn/rubber
     *  band. Every check backs off around these — MWE's history shows rubber-banding is the #1 FP source. */
    int lastTeleportTick = -100;

    // --- Anti-knockback window: the server broadcast this exact impulse (S12) when the player was
    // damaged; kbStart* is where they stood. Three ticks later the detector compares realized
    // displacement against the impulse direction.
    boolean kbPending;
    int kbTick;
    double kbVX, kbVY, kbVZ;
    double kbStartX, kbStartY, kbStartZ;

    // --- Multi-victim (forcefield): distinct victims attributed to this player's swings in one tick.
    int victimsTick = -1;
    int lastVictimId = -1;

    // Violation levels. Constants follow the ported checks' published tuning (MWE for wall/autoblock/
    // eat/noslow); anti-KB is our own synthesis with first-guess numbers pending live tuning. Every VL
    // decays: wall/eat every tick (fast, event-magnitude designs), the rest at a slow -1/s backstop (see
    // CheaterDetector.tickPlayer) so stray coincidences across a long game can never accumulate to a flag.
    final Vl wall = new Vl(1, 500);       // += min(15, wallSteps) * 25 per offending hit
    final Vl antiKb = new Vl(1, 45);      // +15 cancelled KB, -3 clean KB; slow decay
    final Vl autoblock = new Vl(1, 30);   // +5 swing while blocking >5t, -2 clean swing; slow decay
    final Vl eat = new Vl(1, 110);        // +100 per attack mid-eat: 2 within ~90t flags
    final Vl noSlow = new Vl(1, 48);      // +2 per sprint+use tick at speed, -1/-3 clean/rubber-band; slow decay
    final Vl badPitch = new Vl(1, 4);     // +1 per impossible |pitch| > 90.4° packet; slow decay
    final Vl multi = new Vl(1, 3);        // +1 per same-tick 2nd melee-KB victim; slow decay

    // Announcement bookkeeping, indexed by CheaterDetector.CHECK_* id.
    final int[] lastFlagTick = new int[CheaterDetector.CHECK_COUNT];
    final int[] flagCount = new int[CheaterDetector.CHECK_COUNT];

    PlayerData() {
        java.util.Arrays.fill(lastFlagTick, -100000);
    }
}

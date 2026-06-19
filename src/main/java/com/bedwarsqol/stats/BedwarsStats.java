package com.bedwarsqol.stats;

public final class BedwarsStats {

    public enum State { OK, NEVER_PLAYED, NICKED, ERROR }

    /** The six raw Bedwars counters for one mode (or overall) plus derived ratios. */
    public static final class ModeStats {
        public static final ModeStats EMPTY = new ModeStats(0, 0, 0, 0, 0, 0);

        public final int finalKills;
        public final int finalDeaths;
        public final double fkdr;
        public final int wins;
        public final int losses;
        public final double wlr;
        public final int kills;
        public final int deaths;
        public final double kd;

        public ModeStats(int finalKills, int finalDeaths, int wins, int losses, int kills, int deaths) {
            this.finalKills = finalKills;
            this.finalDeaths = finalDeaths;
            this.fkdr = ratio(finalKills, finalDeaths);
            this.wins = wins;
            this.losses = losses;
            this.wlr = ratio(wins, losses);
            this.kills = kills;
            this.deaths = deaths;
            this.kd = ratio(kills, deaths);
        }

        public boolean hasGames() {
            return wins != 0 || losses != 0 || kills != 0 || deaths != 0
                    || finalKills != 0 || finalDeaths != 0;
        }
    }

    public final State state;
    public final String displayName;
    public final int networkLevel;
    /** Pre-colored §-prefixed rank label, e.g. {@code §b[MVP§c+§b]}; empty for no rank. */
    public final String rankPrefix;

    public final ModeStats overall;
    public final ModeStats solo;
    public final ModeStats doubles;
    public final ModeStats threes;
    public final ModeStats fours;

    // Flat overall fields kept so existing callers (and disk cache) keep working.
    public final int finalKills;
    public final int finalDeaths;
    public final double fkdr;
    public final int wins;
    public final int losses;
    public final double wlr;
    public final int kills;
    public final int deaths;
    public final double kd;

    private BedwarsStats(State state, String displayName, int networkLevel, String rankPrefix,
                         ModeStats overall, ModeStats solo, ModeStats doubles,
                         ModeStats threes, ModeStats fours) {
        this.state = state;
        this.displayName = displayName;
        this.networkLevel = Math.max(0, networkLevel);
        this.rankPrefix = rankPrefix == null ? "" : rankPrefix;
        this.overall = overall == null ? ModeStats.EMPTY : overall;
        this.solo = solo == null ? ModeStats.EMPTY : solo;
        this.doubles = doubles == null ? ModeStats.EMPTY : doubles;
        this.threes = threes == null ? ModeStats.EMPTY : threes;
        this.fours = fours == null ? ModeStats.EMPTY : fours;

        this.finalKills = this.overall.finalKills;
        this.finalDeaths = this.overall.finalDeaths;
        this.fkdr = this.overall.fkdr;
        this.wins = this.overall.wins;
        this.losses = this.overall.losses;
        this.wlr = this.overall.wlr;
        this.kills = this.overall.kills;
        this.deaths = this.overall.deaths;
        this.kd = this.overall.kd;
    }

    public static BedwarsStats nicked() {
        return new BedwarsStats(State.NICKED, null, 0, "", null, null, null, null, null);
    }

    public static BedwarsStats error() {
        return new BedwarsStats(State.ERROR, null, 0, "", null, null, null, null, null);
    }

    public static BedwarsStats neverPlayed(String displayName) {
        return new BedwarsStats(State.NEVER_PLAYED, displayName, 0, "", null, null, null, null, null);
    }

    public static BedwarsStats ok(String displayName, int networkLevel, String rankPrefix,
                                  ModeStats overall, ModeStats solo, ModeStats doubles,
                                  ModeStats threes, ModeStats fours) {
        return new BedwarsStats(State.OK, displayName, networkLevel, rankPrefix,
                overall, solo, doubles, threes, fours);
    }

    /** The stats for {@code mode}, falling back to overall when that mode has no games. */
    public ModeStats statsFor(BedwarsMode mode) {
        ModeStats m;
        switch (mode) {
            case SOLO:    m = solo; break;
            case DOUBLES: m = doubles; break;
            case THREES:  m = threes; break;
            case FOURS:   m = fours; break;
            default:      m = overall; break;
        }
        return m != null && m.hasGames() ? m : overall;
    }

    /**
     * Threat ramp by FKDR alone (star level is unavailable from the backend): White &lt; 2 &rarr;
     * Yellow 2-5 &rarr; Orange (gold) 5-10 &rarr; Red 10+. Monotonic, so a weak player is never
     * flagged as a threat (the old ramp painted both {@code <1} and {@code >=10} red).
     */
    public static String fkdrColor(double fkdr) {
        if (fkdr < 2.0)  return "§f";  // White  - low threat
        if (fkdr < 5.0)  return "§e";  // Yellow
        if (fkdr < 10.0) return "§6";  // Orange (gold)
        return "§c";                   // Red    - sweat
    }

    public String formatForNametag(BedwarsMode mode, boolean showLevel, boolean showRank) {
        String special = specialLabel();
        if (special != null) return special;
        ModeStats m = statsFor(mode);
        StringBuilder sb = new StringBuilder();
        appendPrefix(sb, showLevel, showRank);
        sb.append(fkdrColor(m.fkdr)).append("FKDR ").append(fmt2(m.fkdr)).append("§r");
        return sb.toString();
    }

    public String formatForTab(BedwarsMode mode, boolean showLevel, boolean showRank) {
        String special = specialLabel();
        if (special != null) return special;
        ModeStats m = statsFor(mode);
        StringBuilder sb = new StringBuilder();
        appendPrefix(sb, showLevel, showRank);
        sb.append("§7FKDR: ").append(fkdrColor(m.fkdr)).append(fmt2(m.fkdr))
                .append(" §7WLR: §f").append(fmt2(m.wlr));
        return sb.toString();
    }

    /** The bracketed label for non-OK states (or null when stats should render). */
    private String specialLabel() {
        switch (state) {
            case NICKED:       return "§8[Nicked]";
            // A transient fetch failure is NOT an answer — render nothing (like a still-loading player)
            // and let the cache retry, so real players never show a misleading "[?]". Only genuinely
            // nicked players get a bracketed "can't find" tag.
            case ERROR:        return null;
            case NEVER_PLAYED: return "§8[New]";
            default:           return null;
        }
    }

    private void appendPrefix(StringBuilder sb, boolean showLevel, boolean showRank) {
        if (showLevel && networkLevel > 0) {
            sb.append("§7[").append(networkLevel).append("]§r ");
        }
        if (showRank && !rankPrefix.isEmpty()) {
            sb.append(rankPrefix).append("§r ");
        }
    }

    private static double ratio(int a, int b) {
        if (b == 0) return a;
        return (double) a / (double) b;
    }

    private static String fmt2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}

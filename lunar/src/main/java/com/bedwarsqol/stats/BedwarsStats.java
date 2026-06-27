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
    /** Real Bedwars star (level), scraped from the achievements page; 0 when not yet known. */
    public final int bedwarsLevel;
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

    private BedwarsStats(State state, String displayName, int networkLevel, int bedwarsLevel,
                         String rankPrefix, ModeStats overall, ModeStats solo, ModeStats doubles,
                         ModeStats threes, ModeStats fours) {
        this.state = state;
        this.displayName = displayName;
        this.networkLevel = Math.max(0, networkLevel);
        this.bedwarsLevel = Math.max(0, bedwarsLevel);
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
        return new BedwarsStats(State.NICKED, null, 0, 0, "", null, null, null, null, null);
    }

    public static BedwarsStats error() {
        return new BedwarsStats(State.ERROR, null, 0, 0, "", null, null, null, null, null);
    }

    public static BedwarsStats neverPlayed(String displayName) {
        return new BedwarsStats(State.NEVER_PLAYED, displayName, 0, 0, "", null, null, null, null, null);
    }

    public static BedwarsStats ok(String displayName, int networkLevel, int bedwarsLevel, String rankPrefix,
                                  ModeStats overall, ModeStats solo, ModeStats doubles,
                                  ModeStats threes, ModeStats fours) {
        return new BedwarsStats(State.OK, displayName, networkLevel, bedwarsLevel, rankPrefix,
                overall, solo, doubles, threes, fours);
    }

    /**
     * A copy with the Bedwars star filled in — used when the star arrives after the counters (the
     * backend streams it as a follow-up once the achievements page resolves). A no-op for non-OK
     * states or a non-positive/unchanged level.
     */
    public BedwarsStats withLevel(int level) {
        if (state != State.OK || level <= 0 || level == bedwarsLevel) return this;
        return new BedwarsStats(state, displayName, networkLevel, level, rankPrefix,
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

    /**
     * Multi-line stat block for the chat hover card. Returns the lines to append under Hypixel's own
     * rank tooltip; empty when there is nothing useful to show (a transient fetch error), so the caller
     * can fall back to the vanilla card alone.
     */
    public java.util.List<String> formatForHoverCard(BedwarsMode mode, boolean showLevel, boolean showRank) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        switch (state) {
            case NICKED:       out.add("§6§lBedWars §r§8(nicked / not found)"); return out;
            case NEVER_PLAYED: out.add("§6§lBedWars §r§8(never played)");       return out;
            case ERROR:        return out; // empty -> caller keeps the vanilla card and retries later
            default: break;
        }
        ModeStats m = statsFor(mode);
        StringBuilder header = new StringBuilder("§6§lBedWars");
        if (showLevel) {
            String tag = levelTag();
            if (!tag.isEmpty()) header.append(" §r").append(tag);
        }
        if (showRank && !rankPrefix.isEmpty()) header.append(" §r").append(rankPrefix);
        out.add(header.toString());
        out.add("§7FKDR: " + fkdrColor(m.fkdr) + fmt2(m.fkdr) + " §r§8| §7Finals: §f" + num(m.finalKills) + "§7/§f" + num(m.finalDeaths));
        out.add("§7WLR: §f" + fmt2(m.wlr) + " §8| §7W/L: §f" + num(m.wins) + "§7/§f" + num(m.losses));
        out.add("§7K/D: §f" + fmt2(m.kd) + " §8| §7Kills: §f" + num(m.kills));
        return out;
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
        if (showLevel) {
            String tag = levelTag();
            if (!tag.isEmpty()) sb.append(tag).append("§r ");
        }
        if (showRank && !rankPrefix.isEmpty()) {
            sb.append(rankPrefix).append("§r ");
        }
    }

    /**
     * The bracketed level shown before the stats: the real Bedwars star (prestige-colored, with the
     * {@code ✫} symbol) when known, else the network level as a fallback until the star resolves.
     */
    private String levelTag() {
        if (bedwarsLevel > 0) return starTag(bedwarsLevel);
        if (networkLevel > 0) return "§7[" + networkLevel + "]";
        return "";
    }

    /** {@code [<star>✫]} colored by prestige tier (simplified to one color per 100 levels). */
    public static String starTag(int star) {
        return starColor(star) + "[" + star + "✫]§r";
    }

    private static String starColor(int star) {
        switch (star / 100) {
            case 0:  return "§7"; // Stone
            case 1:  return "§f"; // Iron
            case 2:  return "§6"; // Gold
            case 3:  return "§b"; // Diamond
            case 4:  return "§2"; // Emerald
            case 5:  return "§3"; // Sapphire
            case 6:  return "§4"; // Ruby
            case 7:  return "§d"; // Crystal
            case 8:  return "§9"; // Opal
            case 9:  return "§5"; // Amethyst
            default: return "§c"; // Rainbow (1000+) — single stand-in color
        }
    }

    private static double ratio(int a, int b) {
        if (b == 0) return a;
        return (double) a / (double) b;
    }

    private static String fmt2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    private static String num(int n) {
        return String.format(java.util.Locale.US, "%,d", n);
    }
}

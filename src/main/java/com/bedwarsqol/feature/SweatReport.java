package com.bedwarsqol.feature;

import com.mojang.authlib.GameProfile;
import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Once per Bedwars game, after every enemy player's stats have resolved, broadcasts a
 * full rundown to party chat via {@code /pc} so the player's party gets all the info. The
 * first line is the player's own team (average FKDR + each teammate's FKDR); the remaining
 * lines are one per enemy team with the team's average FKDR and every player's FKDR.
 *
 * <p>Teams are grouped by nametag color (Hypixel gives every player a unique 1-person
 * scoreboard team, so color is the only shared team signal — same approach as
 * {@link BedwarsModeDetector}). Teams are ordered by average FKDR (sweatiest first). Lines
 * are plain text (Hypixel strips § from player chat) and sent one per ~0.5s to dodge anti-spam.
 *
 * <p><b>Re-arming:</b> the one-shot {@code sent} flag is reset from the tick loop whenever we
 * leave the active game (debounced), <i>not</i> only on {@link WorldEvent.Load} — Hypixel's
 * BungeeCord server switches don't always fire a fresh client world-load, so relying on it
 * alone left the report stuck after the first game.
 */
public final class SweatReport {

    private static final boolean DEBUG = false;      // logs decisions to the game log + a local chat line

    private static final int TICK_INTERVAL = 10;     // ~0.5s between report ticks and /pc sends
    private static final int REARM_GRACE_SLOTS = 3;  // ~1.5s out of the game before we re-arm (debounce)
    private static final long MAX_WAIT_MS = 25_000;  // give up waiting on unresolved enemies after this
    private static final int MAX_CONTENT = 96;       // 1.8.9 truncates outgoing chat at 100 chars

    private int ticks;
    private boolean sent;
    private long activeSinceMs;
    private int notActiveSlots;
    private final Deque<String> outQueue = new ArrayDeque<String>();

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        // Secondary safety net; the tick loop is the primary re-arm (see class doc).
        rearm("world load");
        outQueue.clear();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        // 1. Drain one queued /pc line per slot (the tick gate above spaces them ~0.5s apart).
        if (!outQueue.isEmpty()) {
            mc.thePlayer.sendChatMessage(outQueue.poll());
            return;
        }

        // 2. Re-arm whenever we're out of an active game (debounced so a momentary scoreboard
        //    flicker mid-game can't trigger a duplicate report).
        boolean inActive = HypixelContext.isOnHypixel() && HypixelContext.isInActiveBedwarsGame();
        if (!inActive) {
            if (++notActiveSlots >= REARM_GRACE_SLOTS) rearm("left active game");
            return;
        }
        notActiveSlots = 0;

        // 3. Toggles.
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.playerStats || !cfg.statsSweatReport) return;

        // 4. Once per game.
        if (sent) return;
        if (activeSinceMs == 0L) activeSinceMs = System.currentTimeMillis();

        NetHandlerPlayClient net = mc.getNetHandler();
        Scoreboard board = mc.theWorld != null ? mc.theWorld.getScoreboard() : null;
        if (net == null || board == null) return;

        char myColor = mc.thePlayer.getGameProfile() != null
                ? teamColor(board, mc.thePlayer.getGameProfile().getName()) : 0;
        List<NetworkPlayerInfo> enemies = collectEnemies(net, board, myColor);
        List<NetworkPlayerInfo> team = collectTeam(net, board, myColor);
        boolean timedOut = System.currentTimeMillis() - activeSinceMs > MAX_WAIT_MS;

        // Teams aren't colored yet (scoreboard still populating) — wait unless we've timed out.
        if (enemies.isEmpty() && !timedOut) return;

        // 5. Wait until every player we'd report on — enemies AND our own team — has resolved (or we
        //    time out). Gating teammates too stops the report firing with our own side still showing
        //    "?" in chat while the live tab list already has them (the tab re-renders every frame; this
        //    report is a one-shot snapshot). Terminal states (OK/NICKED/NEVER_PLAYED) all count as
        //    resolved, so a nicked teammate doesn't stall — and MAX_WAIT_MS is the escape hatch for one
        //    that never resolves, after which it just shows "?" and is skipped in the average.
        int resolved = 0;
        int needed = enemies.size() + team.size();
        for (NetworkPlayerInfo info : enemies) {
            UUID id = info.getGameProfile().getId();
            if (StatsCache.getCached(id) != null) {
                resolved++;
            } else {
                StatsCache.ensureFetched(id, StatsCache.PRIORITY_TAB);
            }
        }
        for (NetworkPlayerInfo info : team) {
            UUID id = info.getGameProfile().getId();
            if (StatsCache.getCached(id) != null) {
                resolved++;
            } else {
                StatsCache.ensureFetched(id, StatsCache.PRIORITY_TAB);
            }
        }
        if (resolved < needed && !timedOut) return;

        // 6. Fire once.
        sent = true;
        List<String> lines = buildLines(enemies, team, board, myColor);
        if (DEBUG) {
            System.out.println("[BedwarsQol][SweatReport] fired: " + lines.size() + " team line(s), "
                    + resolved + "/" + needed + " players resolved, timedOut=" + timedOut);
            local(mc, lines.isEmpty()
                    ? "§8[Sweat] §7No teams detected."
                    : "§8[Sweat] §7Sent report to party.");
        }
        for (String line : lines) outQueue.add("/pc " + line);
    }

    /** Reset to fire again next game. Edge-logs when it actually clears a fired report. */
    private void rearm(String reason) {
        if (DEBUG && sent) System.out.println("[BedwarsQol][SweatReport] re-armed (" + reason + ")");
        sent = false;
        activeSinceMs = 0L;
        notActiveSlots = 0;
    }

    /** Tab players on a real enemy team (colored, and not the local player's color). */
    private static List<NetworkPlayerInfo> collectEnemies(NetHandlerPlayClient net, Scoreboard board, char myColor) {
        List<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>();
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile prof = info.getGameProfile();
            if (prof.getName() == null || prof.getId() == null) continue;
            char color = teamColor(board, prof.getName());
            if (color == 0 || color == myColor) continue;
            out.add(info);
        }
        return out;
    }

    /** Tab players sharing the local player's team color (includes the local player). Empty if our color is unknown. */
    private static List<NetworkPlayerInfo> collectTeam(NetHandlerPlayClient net, Scoreboard board, char myColor) {
        List<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>();
        if (myColor == 0) return out;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile prof = info.getGameProfile();
            if (prof.getName() == null || prof.getId() == null) continue;
            if (teamColor(board, prof.getName()) != myColor) continue;
            out.add(info);
        }
        return out;
    }

    /**
     * Plain-text lines (no "/pc " prefix), one per team ordered sweatiest first — our own team is
     * sorted in among the rest by average FKDR, not pinned to the top. Each line is
     * {@code TEAM(avg): name fkdr, name fkdr, ...} listing every player on that team; our team's
     * label carries a {@code (US)} marker (e.g. {@code RED (US)(2.3): ...}).
     */
    private static List<String> buildLines(List<NetworkPlayerInfo> enemies, List<NetworkPlayerInfo> team,
                                           Scoreboard board, char myColor) {
        BedwarsMode mode = BedwarsModeDetector.current();

        // Group every enemy player under their team color (insertion order is irrelevant — we sort).
        Map<Character, List<Member>> teams = new LinkedHashMap<Character, List<Member>>();
        for (NetworkPlayerInfo info : enemies) {
            GameProfile prof = info.getGameProfile();
            char color = teamColor(board, prof.getName());
            if (color == 0 || color == myColor) continue;
            BedwarsStats stats = StatsCache.getCached(prof.getId());
            List<Member> list = teams.get(color);
            if (list == null) {
                list = new ArrayList<Member>();
                teams.put(color, list);
            }
            list.add(new Member(prof.getName(), stats, mode));
        }

        // Our own team, keyed by our color so it sorts in with the rest by average FKDR. Teammates
        // whose stats didn't resolve show "?" and are skipped in the average (a nicked teammate must
        // not stall or skew the report).
        if (myColor != 0 && !team.isEmpty()) {
            List<Member> ours = new ArrayList<Member>();
            for (NetworkPlayerInfo info : team) {
                GameProfile prof = info.getGameProfile();
                ours.add(new Member(prof.getName(), StatsCache.getCached(prof.getId()), mode));
            }
            teams.put(myColor, ours);
        }

        // Average FKDR per team over its known (OK) players; teams with no known players sort last.
        final Map<Character, Double> avg = new HashMap<Character, Double>();
        for (Map.Entry<Character, List<Member>> e : teams.entrySet()) {
            double sum = 0;
            int n = 0;
            for (Member m : e.getValue()) {
                if (m.known) { sum += m.fkdr; n++; }
            }
            avg.put(e.getKey(), n == 0 ? Double.NaN : sum / n);
        }

        List<Character> order = new ArrayList<Character>(teams.keySet());
        order.sort(new Comparator<Character>() {
            public int compare(Character a, Character b) {
                return Double.compare(sortKey(avg.get(b)), sortKey(avg.get(a)));
            }
        });

        List<String> lines = new ArrayList<String>();
        for (Character c : order) {
            List<Member> members = teams.get(c);
            members.sort(new Comparator<Member>() {
                public int compare(Member a, Member b) {
                    return Double.compare(sortKey(b.fkdr, b.known), sortKey(a.fkdr, a.known));
                }
            });

            Double a = avg.get(c);
            String label = teamName(c) + (c == myColor ? " (US)" : "");
            String prefix = label + "(" + (a == null || Double.isNaN(a) ? "?" : fmt1(a)) + "): ";
            StringBuilder sb = new StringBuilder(prefix);
            int i = 0;
            for (; i < members.size(); i++) {
                Member m = members.get(i);
                String piece = (i == 0 ? "" : ", ") + m.name + " " + m.value;
                if (sb.length() + piece.length() > MAX_CONTENT) break;
                sb.append(piece);
            }
            if (i < members.size()) {
                if (sb.charAt(sb.length() - 1) != ' ') sb.append(", ");
                sb.append('+').append(members.size() - i);
            }
            String content = sb.toString();
            if (content.length() > MAX_CONTENT) content = content.substring(0, MAX_CONTENT);
            lines.add(content);
        }
        return lines;
    }

    private static double sortKey(Double avg) {
        return avg == null || Double.isNaN(avg) ? Double.NEGATIVE_INFINITY : avg;
    }

    private static double sortKey(double fkdr, boolean known) {
        return known ? fkdr : Double.NEGATIVE_INFINITY;
    }

    private static void local(Minecraft mc, String msg) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(msg));
    }

    /** The first Minecraft color code in a player's rendered nametag = their team color. */
    private static char teamColor(Scoreboard board, String name) {
        ScorePlayerTeam team = board.getPlayersTeam(name);
        if (team == null) return 0;
        String formatted = ScorePlayerTeam.formatPlayerName(team, name);
        if (formatted == null) return 0;
        for (int i = 0; i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) == '§') {
                char c = Character.toLowerCase(formatted.charAt(i + 1));
                if ("0123456789abcdef".indexOf(c) >= 0) return c;
            }
        }
        return 0;
    }

    private static String teamName(char color) {
        switch (color) {
            case 'c': return "RED";
            case '9': return "BLUE";
            case 'a': return "GREEN";
            case 'e': return "YELLOW";
            case 'b': return "AQUA";
            case 'd': return "PINK";
            case 'f': return "WHITE";
            case '6': return "GOLD";
            case '7':
            case '8': return "GRAY";
            default:  return "TEAM";
        }
    }

    private static String fmt1(double d) {
        return String.format(Locale.US, "%.1f", d);
    }

    /** One enemy player's display: their FKDR if known, else a short status tag. */
    private static final class Member {
        final String name;
        final boolean known; // stats resolved with an OK state → fkdr is meaningful
        final double fkdr;
        final String value;  // "12.3", "nick", "new", or "?"

        Member(String name, BedwarsStats stats, BedwarsMode mode) {
            this.name = name;
            if (stats != null && stats.state == BedwarsStats.State.OK) {
                this.known = true;
                this.fkdr = stats.statsFor(mode).fkdr;
                this.value = fmt1(this.fkdr);
            } else {
                this.known = false;
                this.fkdr = 0.0;
                this.value = tag(stats);
            }
        }

        private static String tag(BedwarsStats stats) {
            if (stats == null) return "?";
            switch (stats.state) {
                case NICKED:       return "nick";
                case NEVER_PLAYED: return "new";
                default:           return "?";
            }
        }
    }
}

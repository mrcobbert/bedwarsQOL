package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends a player's BedWars stats to the chat hover card. Driven by two mixins: {@code GuiScreenMixin}
 * (for lines Hypixel decorates with its own rank-card hover, i.e. lobby chat) and {@code GuiChatMixin}
 * (for everything Hypixel leaves un-decorated — in-game/queue, party, guild, whispers). Both call
 * {@link #buildTooltip} every frame while the cursor sits over a chat component.
 *
 * <p>Rather than rewrite incoming chat (which would bake a stale, async-empty result into the message
 * forever), we recompute the merged card live: read Hypixel's existing hover lines, look up the cached
 * stats, and return the combined list for the mixin to draw. Stats arrive asynchronously, so a
 * still-fetching player shows a "loading" line and fills in on a later frame.
 *
 * <p>The sender is parsed from the hovered text format-aware (see {@link #extractName}) and resolved
 * preferring a tab-list UUID, falling back to a name-keyed lookup so players who aren't in your tab
 * (nicks, party/guild members in another lobby) still resolve.
 */
public final class ChatHoverStats {

    private ChatHoverStats() {}

    /** A single Minecraft username token: 3-16 of [A-Za-z0-9_]. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Bracketed rank/guild tags dropped before locating the sender, e.g. [MVP+], [Officer], [TAG]. */
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]");
    /** "[rank] <name> joined the lobby!" (optionally wrapped in >>> … <<<); group 1 = name. */
    private static final Pattern JOINED_LOBBY =
            Pattern.compile("^(?:>+\\s*)?(?:\\[[^\\]]*\\]\\s*)+([A-Za-z0-9_]{3,16}) joined the lobby!");
    /** "<name> has joined (n/m)!" — the pregame join counter; group 1 = name. */
    private static final Pattern JOINED_QUEUE =
            Pattern.compile("([A-Za-z0-9_]{3,16}) has joined \\(\\d+/\\d+\\)!");
    /** "<name> has quit!" / "<name> disconnected." — pregame leaves; group 1 = name. */
    private static final Pattern LEFT =
            Pattern.compile("([A-Za-z0-9_]{3,16}) (?:has quit!|disconnected\\.)");

    /**
     * @return the merged hover lines (Hypixel's card + our stats) to render, or {@code null} when this
     *         component is not a player name we handle — in which case the vanilla card renders as-is.
     */
    public static List<String> buildTooltip(IChatComponent hovered) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.playerStats || !cfg.playerStatsChatHover) return null;
        if (hovered == null) return null;
        if (!HypixelContext.isOnHypixel()) return null;
        // No self-hosted backend configured -> we could never fill the card; leave vanilla alone.
        if (cfg.statsBackendUrl == null || cfg.statsBackendUrl.trim().isEmpty()) return null;

        String name = extractName(hovered);
        if (name == null) return null;

        // Start from Hypixel's own hover card (rank/guild) so we extend it instead of replacing it.
        List<String> lines = existingHoverLines(hovered);

        BedwarsStats stats = lookup(name);
        if (stats == null) {
            if (!lines.isEmpty()) lines.add("");
            lines.add("§6§lBedWars §r§7stats §oloading…");
            return lines;
        }

        List<String> statLines = stats.formatForHoverCard(BedwarsModeDetector.current(),
                cfg.playerStatsShowLevel, cfg.playerStatsShowRank);
        if (statLines.isEmpty()) return lines.isEmpty() ? null : lines;
        if (!lines.isEmpty()) lines.add("");
        lines.addAll(statLines);
        return lines;
    }

    /**
     * Resolve stats for a name, preferring a tab-list UUID (a stable key, shared with the nametag/tab
     * cache) and falling back to a name-keyed lookup for players not in your tab list (nicks, party or
     * guild members in another lobby). Triggers an async fetch when nothing is cached yet, and returns
     * {@code null} until it lands so the caller can show a "loading" line.
     */
    private static BedwarsStats lookup(String name) {
        UUID uuid = uuidInTab(name);
        if (uuid != null) {
            BedwarsStats s = StatsCache.getCached(uuid);
            if (s == null) StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_USER);
            return s;
        }
        BedwarsStats s = StatsCache.getCachedByName(name);
        if (s == null) StatsCache.ensureFetchedByName(name, StatsCache.PRIORITY_USER);
        return s;
    }

    /**
     * Pull the sender's username out of the hovered text. Three shapes are recognised, in order:
     * <ol>
     *   <li>Colon-less server lines that still name a player — {@code "<name> joined the lobby!"}
     *       (with or without the {@code >>>}/{@code <<<} MVP++ flourish), {@code "<name> has joined
     *       (n/m)!"}, and {@code "<name> has quit!"}/{@code "disconnected."} — matched by explicit
     *       patterns so the generic logic below doesn't drop them as prose.</li>
     *   <li>{@code "<sender>: <message>"} chat in any channel — see {@link #senderFromHead}.</li>
     *   <li>A lone name token — Hypixel's lobby rank-card name component.</li>
     * </ol>
     * Returns null when no plausible sender is present, so non-player lines keep the vanilla card.
     */
    private static String extractName(IChatComponent hovered) {
        String unformatted = hovered.getUnformattedText();
        if (unformatted == null) return null;
        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(unformatted);
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;

        String shaped = extractFromServerLine(raw);
        if (shaped != null) return shaped;

        int colon = raw.indexOf(':');
        if (colon > 0) return senderFromHead(raw.substring(0, colon));

        // No colon and no known server shape: trust only a lone name token (the rank-card name
        // component), so prose like "Bob has joined" can't drive a bogus lookup on "joined".
        List<String> tokens = nameTokens(BRACKET_TAG.matcher(raw).replaceAll(" "));
        return tokens.size() == 1 ? tokens.get(0) : null;
    }

    /**
     * Names from the colon-less broadcasts Hypixel prints for a player: the rank-gated lobby join
     * ({@code "[MVP+] Name joined the lobby!"}, optionally wrapped in {@code >>> … <<<}), the pregame
     * counter ({@code "Name has joined (7/16)!"}), and the leave lines. Null for anything else.
     */
    private static String extractFromServerLine(String raw) {
        Matcher m = JOINED_LOBBY.matcher(raw);
        if (m.find()) return m.group(1);
        m = JOINED_QUEUE.matcher(raw);
        if (m.matches()) return m.group(1);
        m = LEFT.matcher(raw);
        if (m.matches()) return m.group(1);
        return null;
    }

    /**
     * The sender from a chat line's pre-colon head. A rank/level/guild bracket or a channel prefix
     * ("From", "Party >", "Guild >", …) means the trailing token is the name. A bare head with
     * neither is trusted only when it is a single token that is actually in our tab list, so system
     * labels ("Command Failed:", "Cooldown:") aren't mistaken for players.
     */
    private static String senderFromHead(String head) {
        String lower = head.trim().toLowerCase();
        boolean channel = lower.startsWith("to ") || lower.startsWith("from ")
                || lower.startsWith("party ") || lower.startsWith("guild ")
                || lower.startsWith("officer ") || lower.startsWith("friend ")
                || lower.startsWith("co-op ") || lower.startsWith("shout ");
        boolean hadBracket = head.indexOf('[') >= 0;
        List<String> tokens = nameTokens(BRACKET_TAG.matcher(head).replaceAll(" "));
        if (tokens.isEmpty()) return null;
        String last = tokens.get(tokens.size() - 1);
        if (hadBracket || channel) return last;
        if (tokens.size() != 1) return null;
        return uuidInTab(last) != null ? last : null;
    }

    /** Every {@link #NAME} token in a string, in order. */
    private static List<String> nameTokens(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = NAME.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** UUID for an exact (case-insensitive) tab-list name, or null when the player isn't in your tab. */
    private static UUID uuidInTab(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return null;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile gp = info.getGameProfile();
            if (gp.getId() == null || gp.getName() == null) continue;
            if (gp.getName().equalsIgnoreCase(name)) return gp.getId();
        }
        return null;
    }

    /** Hypixel's existing SHOW_TEXT hover content, split into lines (empty if the name has no card). */
    private static List<String> existingHoverLines(IChatComponent hovered) {
        List<String> lines = new ArrayList<String>();
        ChatStyle style = hovered.getChatStyle();
        if (style == null) return lines;
        HoverEvent he = style.getChatHoverEvent();
        if (he == null || he.getAction() != HoverEvent.Action.SHOW_TEXT || he.getValue() == null) return lines;
        String formatted = he.getValue().getFormattedText();
        if (formatted == null || formatted.isEmpty()) return lines;
        for (String l : formatted.split("\n", -1)) lines.add(l);
        return lines;
    }
}

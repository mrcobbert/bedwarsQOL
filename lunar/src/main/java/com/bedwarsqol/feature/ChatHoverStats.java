package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * <p>The sender is parsed from the hovered text by {@link ChatSender} and resolved preferring a
 * tab-list UUID, falling back to a name-keyed lookup so players who aren't in your tab (nicks,
 * party/guild members in another lobby) still resolve.
 */
public final class ChatHoverStats {

    private ChatHoverStats() {}

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

        String name = ChatSender.extractName(hovered);
        if (name == null) return null;

        // Start from Hypixel's own hover card (rank/guild) so we extend it instead of replacing it.
        List<String> lines = existingHoverLines(hovered);

        BedwarsStats stats = lookup(name);
        if (stats == null) {
            if (!lines.isEmpty()) lines.add("");
            lines.add("§6§lBedWars §r§7stats §oloading…");
            return lines;
        }

        // Honour /bw mode here too: same resolver as the inline FKDR bracket (forced mode, else live
        // per-game detection), so setting e.g. 4s switches the hover card to 4s-specific stats.
        List<String> statLines = stats.formatForHoverCard(BedwarsModeDetector.displayMode(cfg),
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
        UUID uuid = ChatSender.uuidInTab(name);
        if (uuid != null) {
            BedwarsStats s = StatsCache.getCached(uuid);
            if (s == null) StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_USER);
            return s;
        }
        BedwarsStats s = StatsCache.getCachedByName(name);
        if (s == null) StatsCache.ensureFetchedByName(name, StatsCache.PRIORITY_USER);
        return s;
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

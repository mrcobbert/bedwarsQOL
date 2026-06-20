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
 * Appends a player's BedWars stats to the chat hover card. Driven by {@code GuiScreenMixin}, which
 * calls {@link #buildTooltip} every frame while the cursor sits over a chat component (the chat GUI
 * must be open, which is also what gives you a cursor to hover with).
 *
 * <p>The Hypixel rank "card" you see is a vanilla {@link HoverEvent} SHOW_TEXT tooltip on the name
 * component. Rather than rewrite incoming chat (which would bake a stale, async-empty result into the
 * message forever), we recompute the merged card live: read Hypixel's existing hover lines, look up
 * the cached stats by UUID, and return the combined list for the mixin to draw. Stats arrive
 * asynchronously, so a still-fetching player shows a "loading" line and fills in on a later frame.
 */
public final class ChatHoverStats {

    private ChatHoverStats() {}

    /** Minecraft usernames: 3-16 of [A-Za-z0-9_]. Tab-list matching is the real filter below. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

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

        UUID uuid = resolvePlayer(hovered);
        if (uuid == null) return null;

        // Start from Hypixel's own hover card (rank/guild) so we extend it instead of replacing it.
        List<String> lines = existingHoverLines(hovered);

        BedwarsStats stats = StatsCache.getCached(uuid);
        if (stats == null) {
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_USER);
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

    /** Find the first username token in the hovered text that belongs to a player in the tab list. */
    private static UUID resolvePlayer(IChatComponent hovered) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return null;
        String unformatted = hovered.getUnformattedText();
        if (unformatted == null) return null;
        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(unformatted);
        if (raw == null || raw.isEmpty()) return null;

        Matcher matcher = NAME.matcher(raw);
        while (matcher.find()) {
            String token = matcher.group();
            for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) continue;
                GameProfile gp = info.getGameProfile();
                if (gp.getId() == null || gp.getName() == null) continue;
                if (gp.getName().equalsIgnoreCase(token)) return gp.getId();
            }
        }
        return null;
    }
}

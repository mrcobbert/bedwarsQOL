package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.stats.TabListLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Keeps Minecraft's default tab player list, but adds client tweaks on top of it:
 *   - Size (Small/Medium/Large): uniformly scales the whole vanilla list around the top centre.
 *   - Numeric ping (toggle): replaces vanilla's signal-bar icon with the player's latency in ms.
 *   - Hide Header/Footer: suppresses the server-sent text above/below the list.
 *   - BedWars stats overlay, drawn inline on the vanilla list.
 *
 * <p><b>How overlap is prevented (bulletproof).</b> Vanilla's column width is
 * {@code i1 = min(columns * ((head?9:0) + maxName + score + 13), width-50) / columns}. The literal
 * {@code 13} is the per-cell space reserved at the RIGHT for the ping icon. Two things cause names to
 * collide with the ping / stats overlay:
 * <ol>
 *   <li>The {@code 13} reserve is far too small for the numeric ping + stats text, so they get drawn
 *       on top of the name. We {@link #bedwarsqol$widenPingReserve widen that constant} to the widest
 *       ping+stats across all players, so every column reserves exactly enough room on the right.</li>
 *   <li>The {@code width-50} clamp shrinks {@code i1} below the content width when names are long (or
 *       there are many columns), and the name is still drawn at full length — so it overruns the
 *       reserved zone. We {@link #bedwarsqol$noColumnClamp remove that clamp} so the column is always
 *       long enough for the longest name + reserve, then scale the whole list down in
 *       {@link #bedwarsqol$tabStart} to keep it on-screen.</li>
 * </ol>
 */
@Mixin(GuiPlayerTabOverlay.class)
public abstract class GuiPlayerTabOverlayMixin {

    private static final float STATS_SCALE = 0.75F;

    /** Vanilla's hardcoded per-cell reserve (px) for the signal-bar ping icon, in the column-width formula. */
    private static final int VANILLA_PING_RESERVE = 13;
    /** Head-icon width vanilla reserves on a (encrypted/integrated) server — always the case on Hypixel. */
    private static final int HEAD_WIDTH = 9;
    /** Gap (px) vanilla puts between columns. */
    private static final int COLUMN_GAP = 5;

    @Shadow public abstract String getPlayerName(NetworkPlayerInfo info);

    @Shadow private IChatComponent header;
    @Shadow private IChatComponent footer;

    // Stash the header/footer while we blank them for a render pass, so the network-set values survive.
    private IChatComponent bedwarsqol$savedHeader;
    private IChatComponent bedwarsqol$savedFooter;
    private boolean bedwarsqol$restoreHeaderFooter;

    /** Overall tab scale from the Size setting (Large = 1.0, the vanilla full size). */
    private static float bedwarsqol$sizeScale() {
        int size = BedwarsQol.config == null ? 2 : BedwarsQol.config.styledTabListSize;
        switch (size) {
            case 0: return 0.7f;
            case 1: return 0.85f;
            default: return 1.0f;
        }
    }

    /**
     * Scale to draw the stats overlay at, relative to the (already size-scaled) tab matrix. The whole
     * list is GL-scaled by {@link #bedwarsqol$sizeScale()}; if the overlay also used a flat 0.75 it would
     * compound (0.7 * 0.75 ≈ 0.53x at Small) and the numbers would look tiny and cramped. Dividing the
     * outer scale back out keeps the overlay's on-screen size roughly constant across Size settings,
     * capped at 1.0 so it never grows past its full-size look. The reserve math and the draw must use
     * this same value so the reserved gap and the drawn text stay in sync.
     */
    private static float bedwarsqol$statsScale() {
        float s = bedwarsqol$sizeScale();
        return Math.min(STATS_SCALE / s, 1.0f);
    }

    /** Whether to show the numeric "123ms" ping instead of vanilla's signal-bar icon. */
    private static boolean bedwarsqol$pingEnabled() {
        return BedwarsQol.config == null || BedwarsQol.config.tabNumericPing;
    }

    private static String bedwarsqol$pingText(int ping) {
        return ping < 0 ? "?" : ping + "ms";
    }

    /** Width (px) the right-hand zone needs for one player: ping value (or vanilla bar) + stats overlay. */
    private static int bedwarsqol$rightReserveFor(FontRenderer fr, NetworkPlayerInfo info, boolean pingOn) {
        // Ping value + 1px right margin + 2px gap before the stats; or vanilla's 13px bar reserve when off.
        int reserve = pingOn ? fr.getStringWidth(bedwarsqol$pingText(info.getResponseTime())) + 3 : VANILLA_PING_RESERVE;
        String stats = TabListLookup.statsTextForPlayerInfo(info);
        if (stats != null && !stats.isEmpty()) {
            // Scaled stats width + 2px gap to the score column + 2px safety.
            reserve += (int) Math.ceil(fr.getStringWidth(stats) * bedwarsqol$statsScale()) + 4;
        }
        return reserve;
    }

    /**
     * Widens vanilla's {@code +13} ping-icon reserve in the column-width formula to whatever the widest
     * ping value + stats overlay actually needs across every player on the list. Applied uniformly to
     * every column, so the name column shrinks by exactly the reserved amount and names can never overlap
     * the ping or the stats.
     */
    @ModifyConstant(method = "renderPlayerlist", constant = @Constant(intValue = VANILLA_PING_RESERVE))
    private int bedwarsqol$widenPingReserve(int original) {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return original;
        FontRenderer fr = mc.fontRendererObj;
        boolean pingOn = bedwarsqol$pingEnabled();
        int max = original;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null) continue;
            int r = bedwarsqol$rightReserveFor(fr, info, pingOn);
            if (r > max) max = r;
        }
        return max;
    }

    /**
     * Removes vanilla's {@code width-50} clamp on the column width (the 2nd {@code Math.min(int,int)} in
     * {@code renderPlayerlist}). Vanilla clamps the column to the screen, which truncates it below the
     * content width and makes long names overlap the ping/stats. We keep the full natural width — so the
     * column is always long enough for the longest name + reserve — and instead scale the whole list to
     * fit the screen in {@link #bedwarsqol$tabStart}. Returning the natural width (first arg) leaves the
     * clamp a no-op.
     */
    @Redirect(method = "renderPlayerlist",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I", ordinal = 1))
    private int bedwarsqol$noColumnClamp(int natural, int screenCap) {
        return natural;
    }

    /** Number of columns vanilla splits {@code n} players into (max 20 rows per column). */
    private static int bedwarsqol$columns(int n) {
        int columns = 1, rows = n;
        while (rows > 20) {
            columns++;
            rows = (n + columns - 1) / columns;
        }
        return columns;
    }

    /**
     * The full, unclamped on-screen width of the tab grid: {@code columns * i1 + (columns-1) * gap},
     * mirroring vanilla's own geometry but with our widened reserve. Used to decide how far to scale the
     * list down so it always fits the screen.
     */
    private int bedwarsqol$naturalTabWidth(Scoreboard scoreboard, ScoreObjective objective) {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return 0;
        FontRenderer fr = mc.fontRendererObj;
        Collection<NetworkPlayerInfo> all = net.getPlayerInfoMap();
        int n = Math.min(all.size(), 80);
        if (n <= 0) return 0;

        boolean pingOn = bedwarsqol$pingEnabled();
        boolean hearts = objective != null
                && objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;
        int maxName = 0, reserve = VANILLA_PING_RESERVE, scoreW = 0;
        for (NetworkPlayerInfo info : all) {
            if (info == null) continue;
            maxName = Math.max(maxName, fr.getStringWidth(getPlayerName(info)));
            reserve = Math.max(reserve, bedwarsqol$rightReserveFor(fr, info, pingOn));
            if (objective != null && !hearts && scoreboard != null && info.getGameProfile() != null) {
                Score sc = scoreboard.getValueFromObjective(info.getGameProfile().getName(), objective);
                if (sc != null) scoreW = Math.max(scoreW, fr.getStringWidth(" " + sc.getScorePoints()));
            }
        }
        int score = objective == null ? 0 : (hearts ? 90 : scoreW);
        int columns = bedwarsqol$columns(n);
        int i1 = HEAD_WIDTH + maxName + score + reserve;
        return columns * i1 + (columns - 1) * COLUMN_GAP;
    }

    @Inject(method = "renderPlayerlist", at = @At("HEAD"))
    private void bedwarsqol$tabStart(int width, Scoreboard scoreboard, ScoreObjective objective, CallbackInfo ci) {
        if (BedwarsQol.config != null && BedwarsQol.config.tabHideHeaderFooter) {
            bedwarsqol$savedHeader = this.header;
            bedwarsqol$savedFooter = this.footer;
            this.header = null;
            this.footer = null;
            bedwarsqol$restoreHeaderFooter = true;
        }
        GlStateManager.pushMatrix();
        float s = bedwarsqol$sizeScale();
        // With the column clamp removed, a long-named / many-player list can be wider than the screen.
        // Shrink the whole grid to fit (never overlapping), but never enlarge beyond the chosen Size.
        int natural = bedwarsqol$naturalTabWidth(scoreboard, objective);
        if (natural > 0) {
            float fit = (width - 4f) / natural;
            if (fit < s) s = fit;
        }
        if (s != 1.0f) {
            // Anchor at the top centre so the list stays centred at the top of the screen as it scales.
            float ax = width / 2f;
            GlStateManager.translate(ax, 0f, 0f);
            GlStateManager.scale(s, s, 1f);
            GlStateManager.translate(-ax, 0f, 0f);
        }
    }

    @Inject(method = "renderPlayerlist", at = @At("RETURN"))
    private void bedwarsqol$tabEnd(int width, Scoreboard scoreboard, ScoreObjective objective, CallbackInfo ci) {
        GlStateManager.popMatrix();
        if (bedwarsqol$restoreHeaderFooter) {
            this.header = bedwarsqol$savedHeader;
            this.footer = bedwarsqol$savedFooter;
            bedwarsqol$savedHeader = null;
            bedwarsqol$savedFooter = null;
            bedwarsqol$restoreHeaderFooter = false;
        }
    }

    /** Latency colour for the numeric ping (greener = better), grey when unknown. */
    private static int bedwarsqol$pingColor(int ping) {
        if (ping < 0) return 0xFFAAAAAA;
        if (ping <= 80) return 0xFF55FF55;
        if (ping <= 150) return 0xFFFFFF55;
        if (ping <= 300) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    /**
     * Draws the BedWars stats overlay, and — when the Tab Numeric Ping setting is on —
     * replaces vanilla's signal-bar icon with the latency in milliseconds (cancelling the original
     * {@code drawPing} so the bars aren't drawn on top). With numeric ping off, the overlay is still
     * drawn but the vanilla bars are left intact. Everything here lives inside the right-hand zone that
     * {@link #bedwarsqol$widenPingReserve} reserved, so it never reaches the name.
     */
    @Inject(method = "drawPing", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$drawPingAndStats(int slotWidth, int x, int y, NetworkPlayerInfo info, CallbackInfo ci) {
        if (info == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRendererObj;

        boolean pingOn = bedwarsqol$pingEnabled();
        int cellRight = x + slotWidth;
        // Left edge of the ping element — the stats overlay is right-aligned just to its left.
        int rightEdge;
        if (pingOn) {
            int ping = info.getResponseTime();
            String ms = bedwarsqol$pingText(ping);
            int pingX = cellRight - 1 - fr.getStringWidth(ms);
            fr.drawStringWithShadow(ms, (float) pingX, (float) y, bedwarsqol$pingColor(ping));
            rightEdge = pingX - 2;
        } else {
            // Numeric ping off: vanilla draws its bar icon at cellRight - 11 (10px wide); stop left of it.
            rightEdge = cellRight - 11 - 2;
        }

        // Stats / flags overlay, scaled down and right-aligned inside the reserved zone (left of the ping).
        // The reserve in bedwarsqol$widenPingReserve accounts for the vanilla sidebar-score column, so we
        // don't subtract it here — the score (when present) sits in its own column to the left of this zone.
        String stats = TabListLookup.statsTextForPlayerInfo(info);
        if (stats != null && !stats.isEmpty()) {
            float statsScale = bedwarsqol$statsScale();
            float scaledWidth = fr.getStringWidth(stats) * statsScale;
            float drawX = rightEdge - scaledWidth;
            float drawY = y + (9F - 9F * statsScale) / 2F;

            GlStateManager.pushMatrix();
            GlStateManager.translate(drawX, drawY, 0F);
            GlStateManager.scale(statsScale, statsScale, 1F);
            fr.drawStringWithShadow(stats, 0F, 0F, 0xFFFFFFFF);
            GlStateManager.popMatrix();
        }

        // Only suppress vanilla's bars when we've drawn the numeric ping in their place.
        if (pingOn) ci.cancel();
    }
}

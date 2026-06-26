package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import net.weavemc.api.event.WorldEvent;

/**
 * Auto GG: says "gg" once in chat each time a BedWars game ends.
 *
 * <p><b>Game-end signal.</b> Hypixel ends every BedWars game with a centred chat summary whose
 * killer podium opens with a line that — after stripping colour codes and trimming — reads
 * {@code "1st Killer - <name> - <finals>"}. That "1st Killer" line is the long-standing,
 * battle-tested end-of-game marker used by the major Hypixel AutoGG mods (Sk1er / Hyperium):
 * unlike the scoreboard, which lingers through the post-game podium and into the lobby, it
 * appears exactly once, at the instant the game is decided. We key off it (rather than a
 * win/loss title) so Auto GG fires whether you won <i>or</i> lost — the summary is broadcast to
 * everyone still in the game, spectators included.
 *
 * <p><b>False-positive guards.</b> We only fire while on Hypixel, only if we actually played an
 * active game this round ({@code playedGame}, set from the tick loop whenever the slot-2 game
 * objective is live), and only once per game ({@code fired}). A player typing the phrase in chat
 * ("Name: 1st Killer ...") is rejected because we require the trimmed line to <i>start with</i>
 * "1st Killer", which the centred server podium line does but a prefixed chat line never does.
 *
 * <p><b>Re-arming.</b> Mirrors {@link SweatReport}: the one-shot {@code fired}/{@code playedGame}
 * flags are reset from the tick loop after a short debounce of being out of the active game
 * (Hypixel's BungeeCord server hops don't always fire a fresh client world-load), with
 * {@link WorldEvent.Load} as a secondary safety net. The "gg" send is queued onto the tick loop
 * with a ~1s delay so it lands a beat after the end-game burst and off the chat callback.
 */
public final class AutoGg {

    private static final String MESSAGE = "gg";
    private static final int TICK_INTERVAL = 10;     // ~0.5s per tick slot (matches SweatReport cadence)
    private static final int SEND_DELAY_SLOTS = 2;   // ~1s after the trigger before we send "gg"
    private static final int REARM_GRACE_SLOTS = 3;  // ~1.5s out of the active game before we re-arm

    private int ticks;
    private boolean playedGame;   // an active BedWars game has been seen since the last re-arm
    private boolean fired;        // already said gg for the current game
    private int notActiveSlots;
    private int pendingSend;      // >0: tick slots remaining until we post "gg" (0 = idle)

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        rearm();                 // secondary safety net; the tick loop is the primary re-arm
    }

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.autoGg) return;
        if (fired || !playedGame || !HypixelContext.isOnHypixel()) return;
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
        if (msg == null || !msg.trim().startsWith("1st Killer")) return;
        // Arm the send; the tick loop posts it a beat later (off the chat callback, natural delay).
        fired = true;
        pendingSend = SEND_DELAY_SLOTS;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        // Send the queued "gg" once its delay elapses (re-checking the toggle in case it was turned off).
        if (pendingSend > 0 && --pendingSend == 0) {
            Minecraft mc = Minecraft.getMinecraft();
            ClientSettings cfg = BedwarsQol.config;
            if (mc != null && mc.thePlayer != null && cfg != null && cfg.autoGg) {
                mc.thePlayer.sendChatMessage(MESSAGE);
            }
        }

        // Track the game lifecycle for the once-per-game gate and the re-arm (debounced so a brief
        // scoreboard flicker mid-game can't clear our state).
        boolean inActive = HypixelContext.isOnHypixel() && HypixelContext.isInActiveBedwarsGame();
        if (inActive) {
            playedGame = true;
            notActiveSlots = 0;
        } else if (++notActiveSlots >= REARM_GRACE_SLOTS) {
            rearm();             // a pending send is left to complete on its own countdown
        }
    }

    private void rearm() {
        fired = false;
        playedGame = false;
        notActiveSlots = 0;
    }
}

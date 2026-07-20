package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

import java.util.regex.Pattern;

/**
 * Inc Alert: a double pling when, during an active Bedwars game, a teammate (scoreboard team color
 * match, the same signal {@link SweatReport} uses) or any {@code Party >} line says "inc"/"incoming"
 * as a word. Pairs with {@link IncSender}, whose keybind sends {@code /pc INC}.
 *
 * <p>Lunar tree divergence: the Forge tree's ChatNotifications also carries a name-mention alert
 * under a Chat Notifications master; Lunar Client covers the generic chat QOL natively, so here the
 * inc alert stands alone on {@code chatNotifyInc}. Weave routes the mod's own {@code addChatMessage}
 * lines through the same {@code ChatEvent.Received} hook as server chat, so the {@link ModChat} mark
 * check is load-bearing, not belt-and-braces.
 */
public class ChatNotifications {

    /** Minimum gap between two alerts. */
    private static final long DEBOUNCE_MS = 1500L;
    /** "inc" or "incoming" as a word: letters on either side disqualify (since, include, ...). */
    private static final Pattern INC_WORD = Pattern.compile("(?i)(?<![a-z])inc(?:oming)?(?![a-z])");

    private long lastIncMs;
    /** Countdown to the second pling of the double, in client ticks (0 = idle). */
    private int secondPlingTicks;

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatNotifyInc) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) return;
        IChatComponent msg = event.getMessage();
        if (ModChat.isMarked(msg)) return;

        String sender = ChatSender.typedChatName(msg);
        if (sender == null) return; // typed chat only
        String me = mc.thePlayer.getGameProfile().getName();
        if (me == null || sender.equalsIgnoreCase(me)) return;

        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(msg.getUnformattedText());
        if (raw == null) return;
        raw = raw.trim();
        int colon = raw.indexOf(':');
        if (colon < 0 || colon + 1 >= raw.length()) return;
        String body = raw.substring(colon + 1);

        long now = System.currentTimeMillis();
        if (now - lastIncMs > DEBOUNCE_MS
                && INC_WORD.matcher(body).find()
                && HypixelContext.isInActiveBedwarsGame()
                && (raw.startsWith("Party >") || isTeammate(mc, sender, me))) {
            lastIncMs = now;
            mc.thePlayer.playSound("note.pling", 1.0f, 1.6f);
            secondPlingTicks = 3;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (secondPlingTicks <= 0) return;
        if (--secondPlingTicks == 0) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1.0f, 2.0f);
        }
    }

    /** Same-scoreboard-team check via nametag color, the shared team signal on Hypixel. */
    private static boolean isTeammate(Minecraft mc, String sender, String me) {
        Scoreboard board = mc.theWorld != null ? mc.theWorld.getScoreboard() : null;
        if (board == null) return false;
        char mine = teamColor(board, me);
        return mine != 0 && teamColor(board, sender) == mine;
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
}

package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.regex.Pattern;

/**
 * Chat Notifications: sound alerts driven by incoming typed chat. Two sub-alerts under one master:
 *
 * <p><b>Mention</b>: a pling when another player's message body contains your username as a whole
 * word, case-insensitive, on any server. Only typed lines ({@code sender: message}) count — the
 * colon-less server broadcasts (kill feed, join lines) constantly carry your own name in a Bedwars
 * game and would ping every death.
 *
 * <p><b>Inc</b>: a distinct double pling when, during an active Bedwars game, a teammate (scoreboard
 * team color match, the same signal {@link SweatReport} uses) or any {@code Party >} line says
 * "inc"/"incoming" as a word. Pairs with {@link IncSender}, whose keybind sends {@code /pc INC}.
 *
 * <p>Your own messages and the mod's own {@link ModChat}-marked output never alert, and each alert
 * type is debounced so a spam wall can't chain sounds.
 */
public class ChatNotifications {

    /** Minimum gap between two sounds of the same alert type. */
    private static final long DEBOUNCE_MS = 1500L;
    /** "inc" or "incoming" as a word: letters on either side disqualify (since, include, ...). */
    private static final Pattern INC_WORD = Pattern.compile("(?i)(?<![a-z])inc(?:oming)?(?![a-z])");

    private long lastMentionMs;
    private long lastIncMs;
    /** Countdown to the second pling of the inc double, in client ticks (0 = idle). */
    private int secondPlingTicks;

    /** Whole-word matcher for the local player's name, rebuilt if the profile name changes. */
    private Pattern mentionWord;
    private String mentionName;

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2) return; // action bar
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatNotifications) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) return;
        IChatComponent msg = event.message;
        if (msg == null || ModChat.isMarked(msg)) return;

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
        if (cfg.chatNotifyMention && now - lastMentionMs > DEBOUNCE_MS && mentions(body, me)) {
            lastMentionMs = now;
            mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        }
        if (cfg.chatNotifyInc && now - lastIncMs > DEBOUNCE_MS
                && INC_WORD.matcher(body).find()
                && HypixelContext.isInActiveBedwarsGame()
                && (raw.startsWith("Party >") || isTeammate(mc, sender, me))) {
            lastIncMs = now;
            mc.thePlayer.playSound("note.pling", 1.0f, 1.6f);
            secondPlingTicks = 3;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || secondPlingTicks <= 0) return;
        if (--secondPlingTicks == 0) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1.0f, 2.0f);
        }
    }

    private boolean mentions(String body, String me) {
        if (mentionWord == null || !me.equals(mentionName)) {
            mentionName = me;
            mentionWord = Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])");
        }
        return mentionWord.matcher(body).find();
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

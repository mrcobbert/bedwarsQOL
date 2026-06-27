package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Join Alert: prints a red {@code "Party Joined"} to chat when a premade team drops into your
 * Bedwars pregame lobby.
 *
 * <p><b>The signal.</b> A party is teleported in as one unit, so its members' join broadcasts
 * ({@code "<name> has joined (n/m)!"}) all land in the same instant. We count joins that arrive
 * together — within {@link #BURST_TICKS} of each other — and alert when {@link #PARTY_MIN} or more
 * land at once. Solo queuers trickle in one at a time and never reach that count; two parties
 * arriving in quick succession are separated by the gap between their bursts, so each fires its own
 * alert.
 *
 * <p>(Since Hypixel's pregame name obfuscation the lobby hides every enemy's name and skin, so this
 * timing signal — who arrives together — is all we have; identity-based detection is impossible.)
 *
 * <p><b>Guards.</b> Our own join is ignored, a "Sending you to …" teleport drops any open burst, and
 * we only run in the pregame waiting lobby. Chat and tick events both run on the client thread (Hypixel
 * re-queues chat packets onto it), so the burst state needs no synchronisation; {@link WorldEvent.Load}
 * resets as a safety net.
 */
public final class PartyJoinAlert {

    private static final int BURST_TICKS = 3; // joins within ~0.15s count as the same instant
    private static final int PARTY_MIN = 3;   // 3+ names arriving at once = a premade party

    /** "<name> has joined (n/m)!" — the per-join lobby broadcast; group 1 = name. */
    private static final Pattern JOIN =
            Pattern.compile("([A-Za-z0-9_]{3,16}) has joined \\(\\d+/\\d+\\)!");

    private int burstSize;      // joins in the current same-instant burst (our own join excluded)
    private int sinceJoinTicks; // ticks since the last join landed in the burst

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        reset();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.partyJoinAlert) return;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInBedwars()
                || HypixelContext.isInActiveBedwarsGame()) return;
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (msg == null) return;
        handleChat(msg.trim());
    }

    private void handleChat(String msg) {
        if (msg.startsWith("Sending you to ")) { // teleport to a new mini-server
            reset();
            return;
        }
        Matcher m = JOIN.matcher(msg);
        if (!m.matches()) return;

        String self = selfName();
        if (self != null && m.group(1).equalsIgnoreCase(self)) return; // our own arrival isn't a party

        if (sinceJoinTicks > BURST_TICKS) burstSize = 0; // a new burst (the prior one already closed)
        burstSize++;
        sinceJoinTicks = 0;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick();
    }

    private void tick() {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.partyJoinAlert
                || !HypixelContext.isOnHypixel()
                || !HypixelContext.isInBedwars()
                || HypixelContext.isInActiveBedwarsGame()) {
            reset();
            return;
        }
        // Once the burst goes quiet, decide: 3+ at once = a party.
        if (burstSize > 0 && ++sinceJoinTicks > BURST_TICKS) {
            if (burstSize >= PARTY_MIN) announce();
            burstSize = 0;
        }
    }

    private static String selfName() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return null;
        return mc.thePlayer.getName();
    }

    private static void announce() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Party Joined"));
    }

    private void reset() {
        burstSize = 0;
        sinceJoinTicks = 0;
    }
}

package com.bedwarsqol.stats;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;

public final class HypixelContext {

    private HypixelContext() {}

    public static boolean isOnHypixel() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return false;
        if (mc.isSingleplayer()) return false;
        ServerData server = mc.getCurrentServerData();
        if (server == null || server.serverIP == null) return false;
        String ip = server.serverIP.toLowerCase();
        return ip.endsWith("hypixel.net") || ip.equals("mc.hypixel.net") || ip.contains("hypixel");
    }

    public static boolean isInBedwars() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return false;
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) return false;
        String title = stripFormatting(objective.getDisplayName());
        if (title == null) return false;
        String upper = title.toUpperCase();
        return upper.contains("BED WARS") || upper.contains("BEDWARS");
    }

    public static boolean isInActiveBedwarsGame() {
        if (!isInBedwars()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return false;
        return board.getObjectiveInDisplaySlot(2) != null;
    }

    private static String stripFormatting(String s) {
        if (s == null) return null;
        return EnumChatFormatting.getTextWithoutFormattingCodes(s);
    }

    public static ScorePlayerTeam teamForPlayer(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return null;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return null;
        return board.getPlayersTeam(name);
    }
}

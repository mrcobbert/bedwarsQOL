package com.bedwarsqol.command;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.Collections;
import java.util.List;

public class BedwarsQolCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "bedwarsqol";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("hypixelclient");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bedwarsqol [statsurl ...] [statstoken ...]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length > 0 && "statsurl".equalsIgnoreCase(args[0])) {
            handleStatsUrl(sender, args);
            return;
        }
        if (args.length > 0 && "statstoken".equalsIgnoreCase(args[0])) {
            handleStatsToken(sender, args);
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(() -> mc.displayGuiScreen(new SettingsGui()));
    }

    private void handleStatsUrl(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendUrl.isEmpty()) {
                send(sender, "§eNo stats backend URL set. Use §f/bedwarsqol statsurl <url>§e.");
            } else {
                send(sender, "§aStats backend: §f" + cfg.statsBackendUrl);
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args[1]) || "reset".equalsIgnoreCase(args[1])) {
            cfg.statsBackendUrl = ClientSettings.DEFAULT_STATS_BACKEND_URL;
            cfg.save();
            send(sender, "§aCleared stats backend URL (stats disabled until set again).");
            return;
        }
        String url = args[1].trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        cfg.statsBackendUrl = url;
        cfg.save();
        send(sender, "§aSaved stats backend URL.");
    }

    private void handleStatsToken(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendToken.isEmpty()) {
                send(sender, "§eNo stats token set (requests sent unauthenticated).");
            } else {
                send(sender, "§aStats token: §f" + mask(cfg.statsBackendToken));
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args[1]) || "none".equalsIgnoreCase(args[1])) {
            cfg.statsBackendToken = "";
            cfg.save();
            send(sender, "§aCleared stats token (requests now unauthenticated).");
            return;
        }
        cfg.statsBackendToken = args[1].trim();
        cfg.save();
        send(sender, "§aSaved stats token.");
    }

    /** First/last 4 chars only — never echo a full secret to chat. */
    private static String mask(String s) {
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    private static ClientSettings settings() {
        if (BedwarsQol.config == null) BedwarsQol.config = new ClientSettings();
        BedwarsQol.config.sanitize();
        return BedwarsQol.config;
    }

    private static void send(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}

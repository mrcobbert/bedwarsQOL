package com.bedwarsqol.command;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.command.Command;

import java.util.Arrays;

/**
 * {@code /bedwarsqol} (alias {@code /hypixelclient}) — opens the settings GUI, or with
 * {@code statsurl}/{@code statstoken} subcommands configures the stats backend. Ported to Weave's
 * {@link Command}; output goes to local client chat.
 */
public class BedwarsQolCommand extends Command {

    public BedwarsQolCommand() {
        super("bedwarsqol", "hypixelclient");
    }

    @Override
    public void execute(String[] rawArgs) {
        // Weave passes the full token list including the command word ("bedwarsqol"/"hypixelclient")
        // as rawArgs[0]; drop it so args[0] is the first real argument (e.g. "statsurl").
        String[] args = rawArgs.length > 1 ? Arrays.copyOfRange(rawArgs, 1, rawArgs.length) : new String[0];
        if (args.length > 0 && "statsurl".equalsIgnoreCase(args[0])) {
            handleStatsUrl(args);
            return;
        }
        if (args.length > 0 && "statstoken".equalsIgnoreCase(args[0])) {
            handleStatsToken(args);
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(() -> mc.displayGuiScreen(new SettingsGui()));
    }

    private void handleStatsUrl(String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendUrl.isEmpty()) {
                send("§eNo stats backend URL set. Use §f/bedwarsqol statsurl <url>§e.");
            } else {
                send("§aStats backend: §f" + cfg.statsBackendUrl);
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args[1]) || "reset".equalsIgnoreCase(args[1])) {
            cfg.statsBackendUrl = ClientSettings.DEFAULT_STATS_BACKEND_URL;
            cfg.save();
            send("§aCleared stats backend URL (stats disabled until set again).");
            return;
        }
        String url = args[1].trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        cfg.statsBackendUrl = url;
        cfg.save();
        send("§aSaved stats backend URL.");
    }

    private void handleStatsToken(String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendToken.isEmpty()) {
                send("§eNo stats token set (requests sent unauthenticated).");
            } else {
                send("§aStats token: §f" + mask(cfg.statsBackendToken));
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args[1]) || "none".equalsIgnoreCase(args[1])) {
            cfg.statsBackendToken = "";
            cfg.save();
            send("§aCleared stats token (requests now unauthenticated).");
            return;
        }
        cfg.statsBackendToken = args[1].trim();
        cfg.save();
        send("§aSaved stats token.");
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

    private static void send(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        IChatComponent c = new ChatComponentText(message);
        mc.addScheduledTask(() -> mc.thePlayer.addChatMessage(c));
    }
}

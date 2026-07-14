package com.bedwarsqol.command;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.ChatNameTags;
import com.bedwarsqol.feature.ModChat;
import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.command.Command;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /bw} — the mod's single command (aliases {@code /bedwarsqol}, {@code /hypixelclient}), ported
 * to Weave's {@link Command}. No args opens the settings GUI; {@code mode} switches the in-chat FKDR
 * gamemode; {@code stats}/a bare player name prints a stats card; {@code statsurl}/{@code statstoken}
 * configure the backend; {@code help} lists everything. Output goes to local client chat.
 */
public class BedwarsQolCommand extends Command {

    public BedwarsQolCommand() {
        super("bw", "bedwarsqol", "hypixelclient");
    }

    @Override
    public void execute(String[] rawArgs) {
        // Weave passes the full token list including the command word as rawArgs[0]; drop it so args[0]
        // is the first real argument (e.g. "mode", "stats", or a player name).
        String[] args = rawArgs.length > 1 ? Arrays.copyOfRange(rawArgs, 1, rawArgs.length) : new String[0];
        // Guard the whole dispatch. Weave cancels the outgoing chat only AFTER execute() returns
        // normally, so ANY exception escaping here would leak the raw "/bw ..." to the server as a chat
        // message — which is exactly why a failing subcommand showed up as "Unknown command". Report
        // locally and swallow instead.
        try {
            dispatch(args);
        } catch (Throwable t) {
            try { send("§cCommand failed: §f" + describe(t)); } catch (Throwable ignored) { }
        }
    }

    private void dispatch(String[] args) {
        if (args.length == 0) {
            openGui();
            return;
        }
        switch (args[0].toLowerCase(Locale.US)) {
            case "help":
            case "?":
                sendHelp();
                return;
            case "mode":
                handleMode(args);
                return;
            case "statsurl":
                handleStatsUrl(args);
                return;
            case "statstoken":
                handleStatsToken(args);
                return;
            case "menu":
            case "gui":
            case "settings":
            case "config":
                openGui();
                return;
            case "stats":
                BedwarsStatsCommand.showStats(Arrays.copyOfRange(args, 1, args.length));
                return;
            default:
                // A non-reserved first token is a player name → stats card.
                BedwarsStatsCommand.showStats(args);
                return;
        }
    }

    private static void openGui() {
        // Defer opening to a later tick. This runs synchronously inside chat-send dispatch (Weave's
        // ChatEvent.Sent), and right after we return Minecraft's GuiChat calls displayGuiScreen(null) to
        // close the chat box — which would instantly close a GUI opened inline. addScheduledTask runs
        // its task inline when called ON the client thread (so it does NOT defer); hopping onto a
        // short-lived thread makes the nested addScheduledTask enqueue instead, so the GUI opens next
        // tick, after the chat box is already gone.
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        Thread t = new Thread(() -> mc.addScheduledTask(() -> mc.displayGuiScreen(new SettingsGui())),
                "BedwarsQol-OpenGui");
        t.setDaemon(true);
        t.start();
    }

    /** A short human description of a throwable for a one-line chat error. */
    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private void sendHelp() {
        send("§7§m----§r §6§lBedwarsQOL §r§7(/bw)§r §7§m----");
        send("§f/bw §7— open the settings menu");
        send("§f/bw <player> §7— a player's Bedwars stats");
        send("§f/bw stats §7— your own stats");
        send("§f/bw mode <auto|all|solo|2s|3s|4s> §7— chat FKDR gamemode");
        send("§f/bw statsurl <url> §7— set the stats backend");
        send("§f/bw statstoken <token> §7— set the backend token");
        send("§f/bw help §7— this page");
        send("§7§m------------------------------");
    }

    private void handleMode(String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            send("§eChat stats FKDR mode: §f" + modeLabel(cfg.chatStatsMode));
            send("§7Change with §f/bw mode <auto|all|solo|2s|3s|4s>§7.");
            return;
        }
        String canonical = canonicalMode(args[1]);
        if (canonical == null) {
            send("§cUnknown mode '§f" + args[1] + "§c'. Options: §fauto, all, solo, 2s, 3s, 4s§c.");
            return;
        }
        cfg.chatStatsMode = canonical;
        cfg.save();
        send("§aChat now shows §f" + modeLabel(canonical) + "§a FKDR. Updating existing lines…");
        ChatNameTags.refreshDisplayMode();
    }

    /** Canonical stored token for a user-typed mode word, or null when unrecognised. */
    private static String canonicalMode(String raw) {
        switch (raw.trim().toLowerCase(Locale.US)) {
            case "auto": case "detect": case "default": return "auto";
            case "all": case "overall": return "overall";
            case "solo": case "solos": case "1s": case "1v1": return "solo";
            case "doubles": case "double": case "2s": case "2v2v2v2": return "doubles";
            case "threes": case "three": case "3s": case "3v3v3v3": return "threes";
            case "fours": case "four": case "4s": case "4v4v4v4": return "fours";
            default: return null;
        }
    }

    /** Human label for a stored mode token. */
    private static String modeLabel(String canonical) {
        if (canonical == null) return "Auto (per-game)";
        switch (canonical) {
            case "overall": return "Overall";
            case "solo": return "Solo";
            case "doubles": return "Doubles (2v2v2v2)";
            case "threes": return "3v3v3v3";
            case "fours": return "4v4v4v4";
            default: return "Auto (per-game)";
        }
    }

    private void handleStatsUrl(String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendUrl.isEmpty()) {
                send("§eNo stats backend URL set. Use §f/bw statsurl <url>§e.");
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
        IChatComponent c = ModChat.mark(new ChatComponentText(message));
        mc.addScheduledTask(() -> mc.thePlayer.addChatMessage(c));
    }
}

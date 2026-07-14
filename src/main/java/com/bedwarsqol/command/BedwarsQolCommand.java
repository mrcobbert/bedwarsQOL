package com.bedwarsqol.command;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.ChatNameTags;
import com.bedwarsqol.feature.ModChat;
import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /bw} — the mod's single command (aliases {@code /bedwarsqol}, {@code /hypixelclient}).
 * <ul>
 *   <li>{@code /bw} — open the settings GUI</li>
 *   <li>{@code /bw <player>} / {@code /bw stats [player]} — print a Bedwars stats card</li>
 *   <li>{@code /bw mode <auto|all|solo|2s|3s|4s>} — set the in-chat FKDR gamemode (back-patches chat)</li>
 *   <li>{@code /bw statsurl|statstoken ...} — configure the stats backend</li>
 *   <li>{@code /bw help} — list all subcommands</li>
 * </ul>
 * A first token that isn't one of the reserved subcommands is treated as a player name for the card.
 */
public class BedwarsQolCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "bw";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("bedwarsqol", "hypixelclient");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bw [help|mode|stats|statsurl|statstoken]";
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
        // Guard the whole dispatch so a failing subcommand can never escape as an unhandled command.
        try {
            dispatch(sender, args);
        } catch (Throwable t) {
            try { send(sender, "§cCommand failed: §f" + describe(t)); } catch (Throwable ignored) { }
        }
    }

    private void dispatch(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            openGui();
            return;
        }
        switch (args[0].toLowerCase(Locale.US)) {
            case "help":
            case "?":
                sendHelp(sender);
                return;
            case "mode":
                handleMode(sender, args);
                return;
            case "statsurl":
                handleStatsUrl(sender, args);
                return;
            case "statstoken":
                handleStatsToken(sender, args);
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
        // Defer opening to a later tick. This runs synchronously inside client-command handling (which
        // fires during chat-send), and right after we return Minecraft's GuiChat calls
        // displayGuiScreen(null) to close the chat box — which would instantly close a GUI opened
        // inline. addScheduledTask runs its task inline when called ON the client thread (so it does NOT
        // defer); hopping onto a short-lived thread makes the nested addScheduledTask enqueue instead,
        // so the GUI opens next tick, after the chat box is already gone.
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

    private void sendHelp(ICommandSender sender) {
        send(sender, "§7§m----§r §6§lBedwarsQOL §r§7(/bw)§r §7§m----");
        send(sender, "§f/bw §7— open the settings menu");
        send(sender, "§f/bw <player> §7— a player's Bedwars stats");
        send(sender, "§f/bw stats §7— your own stats");
        send(sender, "§f/bw mode <auto|all|solo|2s|3s|4s> §7— chat FKDR gamemode");
        send(sender, "§f/bw statsurl <url> §7— set the stats backend");
        send(sender, "§f/bw statstoken <token> §7— set the backend token");
        send(sender, "§f/bw help §7— this page");
        send(sender, "§7§m------------------------------");
    }

    private void handleMode(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            send(sender, "§eChat stats FKDR mode: §f" + modeLabel(cfg.chatStatsMode));
            send(sender, "§7Change with §f/bw mode <auto|all|solo|2s|3s|4s>§7.");
            return;
        }
        String canonical = canonicalMode(args[1]);
        if (canonical == null) {
            send(sender, "§cUnknown mode '§f" + args[1] + "§c'. Options: §fauto, all, solo, 2s, 3s, 4s§c.");
            return;
        }
        cfg.chatStatsMode = canonical;
        cfg.save();
        send(sender, "§aChat now shows §f" + modeLabel(canonical) + "§a FKDR. Updating existing lines…");
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

    private void handleStatsUrl(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendUrl.isEmpty()) {
                send(sender, "§eNo stats backend URL set. Use §f/bw statsurl <url>§e.");
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
        sender.addChatMessage(ModChat.mark(new ChatComponentText(message)));
    }
}

package com.bedwarsqol;

import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.command.BedwarsStatsCommand;
import com.bedwarsqol.command.BedwarsQolCommand;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import com.bedwarsqol.feature.AutoGg;
import com.bedwarsqol.feature.ClickTracker;
import com.bedwarsqol.feature.TntFuseDisplay;
import com.bedwarsqol.feature.KeybindRegistry;
import com.bedwarsqol.feature.NametagStats;
import com.bedwarsqol.feature.PauseKeyHandler;
import com.bedwarsqol.feature.PingTracker;
import com.bedwarsqol.feature.SettingsKeyHandler;
import com.bedwarsqol.feature.SweatReport;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.stats.BedwarsModeDetector;
import net.weavemc.api.ModInitializer;
import net.weavemc.api.command.CommandBus;
import net.weavemc.api.event.EventBus;

/**
 * Weave entry point for BedwarsQOL inside Lunar Client. Replaces the old Forge {@code @Mod} lifecycle:
 * {@link #init()} loads the config, subscribes every feature to the Weave {@link EventBus}, and
 * registers the chat commands on the {@link CommandBus}. Keybinds are handled directly off Weave's
 * KeyboardEvent inside the relevant features (no vanilla {@code KeyBinding} registration), and the
 * block-overlay feature is driven by {@code mixin/RenderGlobalMixin} instead of a Forge event.
 */
public class BedwarsQol implements ModInitializer {

    public static final String MODID = "bedwarsqol";
    public static final String NAME = "BedwarsQOL";
    public static final String VERSION = "0.3.0";

    public static ClientSettings config;

    @Override
    public void init() {
        config = SettingsManager.load();

        EventBus.subscribe(new KeybindRegistry());
        EventBus.subscribe(new SettingsKeyHandler());
        EventBus.subscribe(new PauseKeyHandler());
        EventBus.subscribe(new BedwarsHudRenderer());
        EventBus.subscribe(new NametagStats());
        EventBus.subscribe(new BedwarsModeDetector());
        EventBus.subscribe(new SweatReport());
        EventBus.subscribe(new AutoGg());
        EventBus.subscribe(new GeneratorTracker());
        EventBus.subscribe(new PingTracker());
        EventBus.subscribe(new ClickTracker());
        EventBus.subscribe(new TntFuseDisplay());

        CommandBus.register(new BedwarsStatsCommand(), new BedwarsQolCommand());

        System.out.println("[BedwarsQOL] Weave mod initialized (v" + VERSION + ")");
    }
}

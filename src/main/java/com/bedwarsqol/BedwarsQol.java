package com.bedwarsqol;

import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.command.BedwarsStatsCommand;
import com.bedwarsqol.command.BedwarsQolCommand;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import com.bedwarsqol.feature.AutoGg;
import com.bedwarsqol.feature.BlockOverlayRenderer;
import com.bedwarsqol.feature.ClickTracker;
import com.bedwarsqol.feature.TntFuseDisplay;
import com.bedwarsqol.feature.NametagStats;
import com.bedwarsqol.feature.PartyJoinAlert;
import com.bedwarsqol.feature.PauseKeyHandler;
import com.bedwarsqol.feature.PingTracker;
import com.bedwarsqol.feature.SettingsKeyHandler;
import com.bedwarsqol.feature.SweatReport;
import com.bedwarsqol.feature.dummy.TestDummyHandler;
import com.bedwarsqol.feature.dummy.EntityTestDummy;
import com.bedwarsqol.feature.dummy.RenderTestDummy;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.stats.BedwarsModeDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;

@Mod(modid = BedwarsQol.MODID, name = BedwarsQol.NAME, version = BedwarsQol.VERSION)
public class BedwarsQol {

    public static final String MODID = "@ID@";
    public static final String NAME = "@NAME@";
    public static final String VERSION = "@VER@";

    @Mod.Instance(MODID)
    public static BedwarsQol INSTANCE;

    public static ClientSettings config;
    public static KeyBinding settingsKeyBinding;
    /** Rebindable key (default unbound) that opens the vanilla pause menu — see {@link com.bedwarsqol.feature.PauseKeyHandler}. */
    public static KeyBinding pauseKeyBinding;

    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        config = SettingsManager.load();
        settingsKeyBinding = new KeyBinding("Open BedwarsQOL Settings", config.settingsKeyCode, "BedwarsQOL");
        ClientRegistry.registerKeyBinding(settingsKeyBinding);
        pauseKeyBinding = new KeyBinding("Open Game Menu", Keyboard.KEY_NONE, "BedwarsQOL");
        ClientRegistry.registerKeyBinding(pauseKeyBinding);
        ClientCommandHandler.instance.registerCommand(new BedwarsStatsCommand());
        ClientCommandHandler.instance.registerCommand(new BedwarsQolCommand());
        MinecraftForge.EVENT_BUS.register(new SettingsKeyHandler(settingsKeyBinding));
        MinecraftForge.EVENT_BUS.register(new PauseKeyHandler(pauseKeyBinding));
        MinecraftForge.EVENT_BUS.register(new BedwarsHudRenderer());
        MinecraftForge.EVENT_BUS.register(new NametagStats());
        MinecraftForge.EVENT_BUS.register(new BedwarsModeDetector());
        MinecraftForge.EVENT_BUS.register(new SweatReport());
        MinecraftForge.EVENT_BUS.register(new AutoGg());
        MinecraftForge.EVENT_BUS.register(new PartyJoinAlert());
        MinecraftForge.EVENT_BUS.register(new GeneratorTracker());
        MinecraftForge.EVENT_BUS.register(new PingTracker());
        MinecraftForge.EVENT_BUS.register(new ClickTracker());
        MinecraftForge.EVENT_BUS.register(new BlockOverlayRenderer());
        MinecraftForge.EVENT_BUS.register(new TntFuseDisplay());
        MinecraftForge.EVENT_BUS.register(new TestDummyHandler());

        // Debug "Test Dummy": one mod entity, rendered as a player. Spawned on the integrated server in
        // singleplayer (hittable) and client-side only in multiplayer (visual) — see TestDummyHandler.
        EntityRegistry.registerModEntity(EntityTestDummy.class, "test_dummy", 0, INSTANCE, 64, 1, true);
        RenderingRegistry.registerEntityRenderingHandler(EntityTestDummy.class,
                new RenderTestDummy(Minecraft.getMinecraft().getRenderManager()));
    }
}

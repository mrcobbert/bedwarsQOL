package com.bedwarsqol.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Opens the vanilla pause menu on a rebindable key, so players who turn on "Disable Esc Menu"
 * ({@link com.bedwarsqol.mixin.MinecraftEscMixin}) can still reach the menu on demand. The
 * KeyBinding lives in Minecraft's native Controls menu (registered in {@code BedwarsQol#onInit}, default
 * unbound). Mirrors {@link SettingsKeyHandler}: acts on the key-down edge while no screen is open.
 */
public class PauseKeyHandler {

    private final KeyBinding keyBinding;

    public PauseKeyHandler(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || keyBinding == null || !keyBinding.isPressed()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayInGameMenu();
    }
}

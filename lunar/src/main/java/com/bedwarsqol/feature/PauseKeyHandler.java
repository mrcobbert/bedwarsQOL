package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Opens the vanilla pause menu on a rebindable key, so players who turn on "Disable Esc Menu"
 * ({@link com.bedwarsqol.mixin.MinecraftEscMixin}) can still reach the menu on demand. Driven by Weave's
 * {@link KeyboardEvent}; the key is set via {@link com.bedwarsqol.config.ClientSettings#pauseKeyCode}
 * (default unbound). Acts on the key-down edge while in-world with no screen open.
 */
public class PauseKeyHandler {

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (!event.getKeyState()) return; // key-down edge only
        if (BedwarsQol.config == null) return;
        int key = BedwarsQol.config.pauseKeyCode;
        if (key == Keyboard.KEY_NONE || event.getKeyCode() != key) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null || mc.thePlayer == null) return;
        mc.displayInGameMenu();
    }
}

package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Opens the BedwarsQOL settings GUI on the configured key (default Right Shift). Driven by Weave's
 * {@link KeyboardEvent} (key-down edge, no screen open) since there is no vanilla {@code KeyBinding}
 * registration under Weave — the key is rebindable via {@link com.bedwarsqol.config.ClientSettings#settingsKeyCode}.
 */
public class SettingsKeyHandler {

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (!event.getKeyState()) return; // key-down edge only
        if (BedwarsQol.config == null) return;
        int key = BedwarsQol.config.settingsKeyCode;
        if (key == Keyboard.KEY_NONE || event.getKeyCode() != key) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayGuiScreen(new SettingsGui());
    }
}

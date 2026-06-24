package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.Keybind;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Fires the user's chat macros ({@link Keybind}). Driven by Weave's {@link KeyboardEvent}: we act on the
 * key-<i>down</i> edge only (one send per press), and only while in-world with no screen open (so a macro
 * never triggers while typing in chat or in a menu). Each bound macro whose key matches the pressed key is
 * sent via the same path as typing into chat (a leading {@code /} runs as a command).
 */
public class KeybindMacros {

    @SubscribeEvent
    public void onKeyInput(KeyboardEvent event) {
        if (!event.getKeyState()) return; // key-down edge only
        if (BedwarsQol.config == null) return;
        List<Keybind> binds = BedwarsQol.config.keybinds;
        if (binds == null || binds.isEmpty()) return;

        int key = event.getKeyCode();
        if (key == Keyboard.KEY_NONE) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) return;

        for (Keybind kb : binds) {
            if (kb == null || kb.keyCode != key || kb.message == null) continue;
            String message = kb.message.trim();
            if (!message.isEmpty()) mc.thePlayer.sendChatMessage(message);
        }
    }
}

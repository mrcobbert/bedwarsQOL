package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.Keybind;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Fires the user's chat macros ({@link Keybind}). {@link InputEvent.KeyInputEvent} is dispatched by
 * Forge only while no GUI screen is open, so a macro never triggers while the player is typing in
 * chat or has a menu open — and it doesn't conflict with the settings key-capture flow. We act on the
 * key-<i>down</i> edge only (one send per press), matching every bound macro to the pressed key and
 * sending its message via the same path as typing into chat (a leading {@code /} runs as a command).
 */
public class KeybindMacros {

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (BedwarsQol.config == null) return;
        List<Keybind> binds = BedwarsQol.config.keybinds;
        if (binds == null || binds.isEmpty()) return;
        if (!Keyboard.getEventKeyState()) return; // key-down edge only

        int key = Keyboard.getEventKey();
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

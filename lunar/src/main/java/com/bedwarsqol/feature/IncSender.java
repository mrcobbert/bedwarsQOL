package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Sends {@code /pc INC} to party chat when the "Send /pc INC" key is pressed (rebindable via
 * Minecraft's Controls menu through {@link KeybindRegistry}, default unbound). Driven by Weave's
 * {@link KeyboardEvent} like {@link SettingsKeyHandler}; the no-screen gate stops typing "inc" in
 * the chat box from triggering it. A 2-second cooldown stops a key mash from spamming party chat
 * into Hypixel's rate limit. The received line then trips teammates' {@link ChatNotifications}.
 */
public class IncSender {

    private static final long COOLDOWN_MS = 2000L;

    private long lastSentMs;

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (!event.getKeyState()) return; // key-down edge only
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.pcIncKey) return;
        int key = cfg.pcIncKeyCode;
        if (key == Keyboard.KEY_NONE || event.getKeyCode() != key) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        if (now - lastSentMs < COOLDOWN_MS) return;
        lastSentMs = now;
        mc.thePlayer.sendChatMessage("/pc INC");
    }
}

package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

/**
 * Sends {@code /pc INC} to party chat when the "Send /pc INC" key (bound in Minecraft's Controls menu
 * under BedwarsQOL, default unbound) is pressed. A 2-second cooldown stops a key mash from spamming
 * party chat into Hypixel's rate limit. The received line then trips teammates'
 * {@link ChatNotifications} inc alert.
 */
public class IncSender {

    private static final long COOLDOWN_MS = 2000L;

    private final KeyBinding keyBinding;
    private long lastSentMs;

    public IncSender(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        // Drain the press before the config gates so presses can't queue up while disabled.
        if (keyBinding == null || !keyBinding.isPressed()) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.pcIncKey) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSentMs < COOLDOWN_MS) return;
        lastSentMs = now;
        mc.thePlayer.sendChatMessage("/pc INC");
    }
}

package com.bedwarsqol.feature;

import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class SettingsKeyHandler {

    private final KeyBinding keyBinding;

    public SettingsKeyHandler(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || keyBinding == null || !keyBinding.isPressed()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayGuiScreen(new SettingsGui());
    }
}

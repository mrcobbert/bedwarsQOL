package com.bedwarsqol.feature;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks left-click clicks-per-second over a rolling one-second window. {@link MouseEvent} fires only
 * while no GUI is open (so menu/inventory clicks don't inflate it), and we count the press edge only
 * ({@code buttonstate == true}). {@link #cps()} evicts stale timestamps on read, so it decays to 0 the
 * moment the player stops clicking. Surfaced as a line in the Info HUD.
 */
public class ClickTracker {

    private static final long WINDOW_MS = 1000L;
    private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<Long>();

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button != 0 || !event.buttonstate) return; // left-button press edge only
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) return; // in-game only
        long now = System.currentTimeMillis();
        LEFT_CLICKS.addLast(now);
        trim(now);
    }

    /** Left clicks in the last second. */
    public static int cps() {
        trim(System.currentTimeMillis());
        return LEFT_CLICKS.size();
    }

    private static void trim(long now) {
        while (!LEFT_CLICKS.isEmpty() && now - LEFT_CLICKS.peekFirst() > WINDOW_MS) {
            LEFT_CLICKS.removeFirst();
        }
    }
}

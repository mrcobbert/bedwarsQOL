package com.bedwarsqol.feature.dummy;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns and clears {@link EntityTestDummy} practice targets for the Debug module.
 *
 * <p>Pressing the configured key ({@code config.dummySpawnKeyCode}, while the module is enabled and no
 * GUI is open) places one dummy on the block face you're looking at; click again for more. Behaviour
 * splits by world:
 * <ul>
 *   <li><b>Singleplayer</b> — the dummy is spawned on the integrated server (via
 *       {@link MinecraftServer#addScheduledTask} so it runs on the server thread), which replicates it
 *       to the client. Vanilla combat, knockback and death all apply: you can hit it like a real player.</li>
 *   <li><b>Multiplayer</b> — the dummy is added to the client world only. It renders as a player but
 *       can't be hit, and <i>nothing</i> is sent to the server (safe on Hypixel).</li>
 * </ul>
 */
public class TestDummyHandler {

    private final Minecraft mc = Minecraft.getMinecraft();

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (BedwarsQol.config == null || !BedwarsQol.config.dummyEnabled) return;
        if (!Keyboard.getEventKeyState()) return; // key-down edge only
        int bound = BedwarsQol.config.dummySpawnKeyCode;
        if (bound == Keyboard.KEY_NONE || Keyboard.getEventKey() != bound) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        spawnAtLookTarget();
    }

    /** Places one dummy on the block face the player is looking at (or a few blocks ahead if none). */
    private void spawnAtLookTarget() {
        double x, y, z;
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mop.getBlockPos() != null) {
            // The empty block adjacent to the face you're aiming at, like placing a block there.
            BlockPos place = mop.getBlockPos().offset(mop.sideHit);
            x = place.getX() + 0.5;
            y = place.getY();
            z = place.getZ() + 0.5;
        } else {
            Vec3 look = mc.thePlayer.getLook(1.0F);
            x = mc.thePlayer.posX + look.xCoord * 3.0;
            y = mc.thePlayer.posY;
            z = mc.thePlayer.posZ + look.zCoord * 3.0;
        }
        final float yaw = mc.thePlayer.rotationYaw + 180.0F; // face the player

        if (mc.isSingleplayer()) {
            final MinecraftServer server = mc.getIntegratedServer();
            if (server == null) return;
            final WorldServer world = server.worldServerForDimension(mc.thePlayer.dimension);
            final double fx = x, fy = y, fz = z;
            server.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    EntityTestDummy dummy = new EntityTestDummy(world);
                    place(dummy, fx, fy, fz, yaw);
                    world.spawnEntityInWorld(dummy);
                }
            });
        } else {
            EntityTestDummy dummy = new EntityTestDummy(mc.theWorld);
            place(dummy, x, y, z, yaw);
            mc.theWorld.spawnEntityInWorld(dummy);
        }
    }

    private static void place(EntityTestDummy dummy, double x, double y, double z, float yaw) {
        dummy.setLocationAndAngles(x, y, z, yaw, 0.0F);
        dummy.rotationYawHead = yaw;
        dummy.renderYawOffset = yaw;
    }

    /** Removes every spawned dummy. In singleplayer this runs on the server thread (and replicates the
     *  removal to the client); in multiplayer it clears the client-only copies directly. */
    public static void clearAll() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null && mc.thePlayer != null) {
            final MinecraftServer server = mc.getIntegratedServer();
            final WorldServer world = server.worldServerForDimension(mc.thePlayer.dimension);
            server.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    killDummies(world.loadedEntityList);
                }
            });
        } else if (mc.theWorld != null) {
            killDummies(mc.theWorld.loadedEntityList);
        }
    }

    /** Snapshot first (setDead mutates the list), then kill every dummy in it. */
    private static void killDummies(List<?> loaded) {
        List<Entity> doomed = new ArrayList<Entity>();
        for (Object o : loaded) {
            if (o instanceof EntityTestDummy) doomed.add((Entity) o);
        }
        for (Entity e : doomed) e.setDead();
    }
}

package com.bedwarsqol.mixin;

import com.bedwarsqol.anticheat.CheaterDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.DataWatcher;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the {@link CheaterDetector} raw server packets: swings/crits (S0B), hurts (S19 status),
 * knockback impulses (S12), state flags (S1C metadata), rotations (S19 head look + S14 look), and
 * block changes (S23/S22, the through-wall check's ghost-block lag compensation).
 *
 * <p>Purely read-only taps at HEAD — nothing is cancelled or altered. Every vanilla handler runs
 * twice (once on the Netty thread, then re-queued onto the client thread); {@code wantsPackets()}
 * accepts only the client-thread pass, so the detector stays single-threaded.
 */
@Mixin(NetHandlerPlayClient.class)
public class NetHandlerPlayClientMixin {

    @Inject(method = "handleAnimation", at = @At("HEAD"))
    private void bedwarsqol$onAnimation(S0BPacketAnimation packetIn, CallbackInfo ci) {
        if (CheaterDetector.wantsPackets()) {
            CheaterDetector.packetAnimation(packetIn.getEntityID(), packetIn.getAnimationType());
        }
    }

    @Inject(method = "handleEntityStatus", at = @At("HEAD"))
    private void bedwarsqol$onEntityStatus(S19PacketEntityStatus packetIn, CallbackInfo ci) {
        if (CheaterDetector.wantsPackets()) {
            CheaterDetector.packetEntityStatus(
                    packetIn.getEntity(Minecraft.getMinecraft().theWorld), packetIn.getOpCode());
        }
    }

    @Inject(method = "handleEntityVelocity", at = @At("HEAD"))
    private void bedwarsqol$onEntityVelocity(S12PacketEntityVelocity packetIn, CallbackInfo ci) {
        if (CheaterDetector.wantsPackets()) {
            CheaterDetector.packetVelocity(packetIn.getEntityID(),
                    packetIn.getMotionX() / 8000.0, packetIn.getMotionY() / 8000.0, packetIn.getMotionZ() / 8000.0);
        }
    }

    @Inject(method = "handleEntityMetadata", at = @At("HEAD"))
    private void bedwarsqol$onEntityMetadata(S1CPacketEntityMetadata packetIn, CallbackInfo ci) {
        if (!CheaterDetector.wantsPackets() || packetIn.func_149376_c() == null) return;
        for (DataWatcher.WatchableObject wo : packetIn.func_149376_c()) {
            if (wo.getDataValueId() == 0 && wo.getObject() instanceof Byte) {
                CheaterDetector.packetMetadataFlags(packetIn.getEntityId(), (Byte) wo.getObject());
            }
        }
    }

    @Inject(method = "handleEntityHeadLook", at = @At("HEAD"))
    private void bedwarsqol$onEntityHeadLook(S19PacketEntityHeadLook packetIn, CallbackInfo ci) {
        if (CheaterDetector.wantsPackets()) {
            CheaterDetector.packetHeadLook(
                    packetIn.getEntity(Minecraft.getMinecraft().theWorld),
                    packetIn.getYaw() * 360.0f / 256.0f);
        }
    }

    @Inject(method = "handleEntityMovement", at = @At("HEAD"))
    private void bedwarsqol$onEntityMovement(S14PacketEntity packetIn, CallbackInfo ci) {
        // func_149060_h = "has rotation"; func_149066_f/func_149063_g = yaw/pitch angle bytes.
        if (CheaterDetector.wantsPackets() && packetIn.func_149060_h()) {
            CheaterDetector.packetLook(
                    packetIn.getEntity(Minecraft.getMinecraft().theWorld),
                    packetIn.func_149066_f() * 360.0f / 256.0f,
                    packetIn.func_149063_g() * 360.0f / 256.0f);
        }
    }

    @Inject(method = "handleBlockChange", at = @At("HEAD"))
    private void bedwarsqol$onBlockChange(S23PacketBlockChange packetIn, CallbackInfo ci) {
        if (CheaterDetector.wantsPackets()) {
            CheaterDetector.packetBlockChange(packetIn.getBlockPosition());
        }
    }

    @Inject(method = "handleMultiBlockChange", at = @At("HEAD"))
    private void bedwarsqol$onMultiBlockChange(S22PacketMultiBlockChange packetIn, CallbackInfo ci) {
        if (!CheaterDetector.wantsPackets() || packetIn.getChangedBlocks() == null) return;
        for (S22PacketMultiBlockChange.BlockUpdateData data : packetIn.getChangedBlocks()) {
            CheaterDetector.packetBlockChange(data.getPos());
        }
    }
}

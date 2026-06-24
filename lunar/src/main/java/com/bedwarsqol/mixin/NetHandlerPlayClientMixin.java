package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.TpsTracker;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the server's time-update packets (sent every 20 server ticks) so {@link TpsTracker}
 * can estimate TPS for the Info HUD.
 */
@Mixin(NetHandlerPlayClient.class)
public class NetHandlerPlayClientMixin {

    @Inject(method = "handleTimeUpdate", at = @At("HEAD"))
    private void bedwarsqol$onTimeUpdate(S03PacketTimeUpdate packetIn, CallbackInfo ci) {
        TpsTracker.onTimeUpdate();
    }
}

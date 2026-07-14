package com.bedwarsqol.mixin;

import com.bedwarsqol.anticheat.CheaterDetector;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records OUR OWN melee attacks for the cheater detector's attribution telemetry: exact knowledge
 * of who we attacked and when, instead of guessing from the swing animation. Read-only HEAD tap —
 * the attack proceeds untouched, the detector only stores the target's entity id and the tick.
 * Shared by both trees (Weave exposes no attack event, and a Forge-only event would diverge).
 */
@Mixin(PlayerControllerMP.class)
public class PlayerControllerMPMixin {

    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void bedwarsqol$onAttackEntity(EntityPlayer playerIn, Entity targetEntity, CallbackInfo ci) {
        CheaterDetector.selfAttack(targetEntity);
    }
}

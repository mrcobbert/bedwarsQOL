package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.BlockOverlayRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes the custom block-selection highlight. In 1.8.9 the selection box is drawn by
 * {@code RenderGlobal.drawSelectionBox(EntityPlayer, MovingObjectPosition, int, float)}. When the
 * block-overlay feature is on, {@link BlockOverlayRenderer#render} draws our own highlight and we
 * cancel the vanilla outline. Replaces the old Forge {@code DrawBlockHighlightEvent} hook.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalMixin {

    @Inject(method = "drawSelectionBox", at = @At("HEAD"), cancellable = true, require = 0)
    private void bedwarsqol$blockOverlay(EntityPlayer player, MovingObjectPosition movingObjectPositionIn,
                                         int execute, float partialTicks, CallbackInfo ci) {
        if (BlockOverlayRenderer.render(player, movingObjectPositionIn, partialTicks)) {
            ci.cancel();
        }
    }
}

package com.bedwarsqol.feature.dummy;

import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;

/**
 * Renders {@link EntityTestDummy} with the vanilla player model ({@link ModelPlayer}) and the default
 * Steve skin, so a spawned dummy looks like a real player. Registered once in {@code BedwarsQol.onInit}
 * via {@code RenderingRegistry.registerEntityRenderingHandler}.
 */
public class RenderTestDummy extends RenderLiving<EntityTestDummy> {

    private static final ResourceLocation SKIN = new ResourceLocation("textures/entity/steve.png");

    public RenderTestDummy(RenderManager renderManager) {
        super(renderManager, new ModelPlayer(0.0F, false), 0.5F);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTestDummy entity) {
        return SKIN;
    }
}

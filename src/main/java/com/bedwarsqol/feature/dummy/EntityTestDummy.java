package com.bedwarsqol.feature.dummy;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.world.World;

/**
 * A practice "Test Dummy" — a player-shaped target entity spawned by the Debug module. It's an
 * {@link EntityCreature} with no AI tasks, so it just stands where it's placed and takes hits,
 * knockback and damage like any mob. {@link RenderTestDummy} draws it with the vanilla player model
 * and the default skin so it reads as a real player.
 *
 * <p>In singleplayer this is spawned on the integrated server (see {@code TestDummyHandler}) so
 * vanilla combat applies natively; in multiplayer the same class is spawned client-side only as a
 * visual, where it can't be hit (and we send nothing to the server). The entity is registered once
 * in {@code BedwarsQol.onInit} via {@code EntityRegistry.registerModEntity}.
 */
public class EntityTestDummy extends EntityCreature {

    public EntityTestDummy(World worldIn) {
        super(worldIn);
        setSize(0.6F, 1.8F); // a player's hitbox
        setCustomNameTag("Test Dummy");
        setAlwaysRenderNameTag(true);
        enablePersistence(); // never despawn on its own
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20.0D);
        // Speed 0: with no AI tasks it never wanders, it only reacts to knockback.
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.0D);
    }

    @Override
    protected boolean canDespawn() {
        return false;
    }

    /** Silent: no idle/hurt/death mob sounds for a practice dummy. */
    @Override
    protected String getLivingSound() {
        return null;
    }
}

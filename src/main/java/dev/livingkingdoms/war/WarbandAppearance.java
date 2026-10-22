package dev.livingkingdoms.war;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Marks warband soldiers so clients can give them their own model without any packet or saved-data schema.
 *
 * <p>The marker is a permanent attribute modifier whose amount is zero: it changes no stat, it is stored with the
 * entity's own NBT, and vanilla already synchronizes max-health modifiers to every client that tracks the entity.
 * Soldiers spawned before 0.20.1 carry no marker and keep the vanilla look.
 */
public final class WarbandAppearance {
    public static final ResourceLocation SOLDIER = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "warband_soldier");
    private static final Holder<Attribute> CARRIER = Attributes.MAX_HEALTH;

    private WarbandAppearance() { }

    /** Idempotent; a zero-amount modifier never affects health, damage or movement. */
    public static void markSoldier(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(CARRIER);
        if (instance == null || instance.hasModifier(SOLDIER)) return;
        instance.addPermanentModifier(new AttributeModifier(SOLDIER, 0, AttributeModifier.Operation.ADD_VALUE));
    }

    /** Safe on both logical sides; the client sees the marker through the synchronized attribute snapshot. */
    public static boolean isSoldier(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(CARRIER);
        return instance != null && instance.hasModifier(SOLDIER);
    }
}

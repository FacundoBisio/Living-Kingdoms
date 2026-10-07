package dev.livingkingdoms.progression;

import dev.livingkingdoms.config.ProgressionConfig;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.EquipmentRules;
import dev.livingkingdoms.progression.domain.LevelValue;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** Small faction-aware loadout upgrades at spawn only; native bows/crossbows and Captain banners remain. */
public final class EquipmentProgression {
    private EquipmentProgression() {}
    public static void apply(Mob mob, Faction faction, LevelValue level) {
        if (!(mob.level() instanceof ServerLevel server) || !server.getServer().isSameThread()) throw new IllegalStateException("Equipment requires server authority");
        if (faction.isAllied()) return;
        if (dev.livingkingdoms.encounter.EncounterMember.read(mob).filter(identity -> identity.faction() == faction).isEmpty()) {
            throw new IllegalArgumentException("Equipment scaling requires a matching managed hostile identity");
        }
        EquipmentRules rules = ProgressionConfig.equipmentRules();
        EquipmentRules.Tier tier = rules.tier(faction, level);
        Item chest = switch (tier) { case BASIC -> null; case LEATHER -> Items.LEATHER_CHESTPLATE; case CHAIN -> Items.CHAINMAIL_CHESTPLATE; case IRON -> Items.IRON_CHESTPLATE; };
        Item helmet = switch (tier) { case BASIC -> null; case LEATHER -> Items.LEATHER_HELMET; case CHAIN -> Items.CHAINMAIL_HELMET; case IRON -> Items.IRON_HELMET; };
        equipEmpty(mob, EquipmentSlot.CHEST, chest);
        if (!(mob instanceof Pillager pillager && pillager.isPatrolLeader())) equipEmpty(mob, EquipmentSlot.HEAD, helmet);
        if (faction == Faction.UNDEAD && mob instanceof Zombie && tier.ordinal() >= EquipmentRules.Tier.CHAIN.ordinal()) equipEmpty(mob, EquipmentSlot.MAINHAND, Items.IRON_SWORD);
        // At most one modest enchantment, preserving existing vanilla enchanted equipment.
        if (rules.enchant(level, mob.getRandom().nextDouble())) {
            ItemStack armor = mob.getItemBySlot(EquipmentSlot.CHEST);
            if (!armor.isEmpty() && !armor.isEnchanted()) armor.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), 1);
        }
    }
    private static void equipEmpty(Mob mob, EquipmentSlot slot, Item item) {
        if (item != null && mob.getItemBySlot(slot).isEmpty()) {
            mob.setItemSlot(slot, new ItemStack(item));
            mob.setDropChance(slot, 0.0F); // Level upgrades are combat difficulty, not an equipment farming loop.
        }
    }
}

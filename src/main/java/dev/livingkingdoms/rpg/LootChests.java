package dev.livingkingdoms.rpg;

import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.rpg.domain.LootRarity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

/**
 * Contents of kingdom loot chests. Each rarity has a guaranteed core plus random extras; higher rarities
 * add whetstones, tomes and, for legendary, a chance at a refined trophy weapon.
 */
public final class LootChests {
    private LootChests() {}

    public static ItemStack chest(LootRarity rarity) {
        return new ItemStack(switch (rarity) {
            case COMMON -> KingdomItems.CHEST_COMMON.get(); case RARE -> KingdomItems.CHEST_RARE.get();
            case EPIC -> KingdomItems.CHEST_EPIC.get(); case LEGENDARY -> KingdomItems.CHEST_LEGENDARY.get();
        });
    }

    public static List<ItemStack> roll(ServerPlayer player, LootRarity rarity) {
        var random = player.getRandom(); var items = new ArrayList<ItemStack>();
        switch (rarity) {
            case COMMON -> {
                items.add(stack(Items.EMERALD, 4 + random.nextInt(5)));
                items.add(random.nextBoolean() ? stack(Items.IRON_INGOT, 4 + random.nextInt(5)) : stack(Items.BREAD, 6 + random.nextInt(5)));
                items.add(stack(Items.EXPERIENCE_BOTTLE, 2 + random.nextInt(3)));
                if (random.nextFloat() < 0.2F) items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get()));
            }
            case RARE -> {
                items.add(stack(Items.EMERALD, 10 + random.nextInt(7)));
                items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 1 + random.nextInt(2)));
                items.add(stack(Items.GOLD_INGOT, 6 + random.nextInt(5)));
                items.add(stack(Items.DIAMOND, 1));
                if (random.nextFloat() < 0.35F) items.add(KingdomTomes.roll(player.serverLevel(), random));
            }
            case EPIC -> {
                items.add(stack(Items.EMERALD, 20 + random.nextInt(11)));
                items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 2 + random.nextInt(2)));
                items.add(KingdomTomes.roll(player.serverLevel(), random));
                items.add(stack(Items.DIAMOND, 2 + random.nextInt(3)));
                if (random.nextFloat() < 0.3F) items.add(stack(Items.GOLDEN_APPLE, 1 + random.nextInt(2)));
            }
            case LEGENDARY -> {
                items.add(stack(Items.EMERALD, 40));
                items.add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 4));
                items.add(KingdomTomes.roll(player.serverLevel(), random));
                items.add(KingdomTomes.roll(player.serverLevel(), random));
                items.add(stack(Items.DIAMOND, 6));
                items.add(stack(Items.NETHERITE_SCRAP, 2));
                if (random.nextFloat() < 0.5F) items.add(stack(Items.ENCHANTED_GOLDEN_APPLE, 1));
                if (random.nextFloat() < 0.3F) {
                    var weapon = new ItemStack(random.nextBoolean() ? Items.DIAMOND_SWORD : Items.DIAMOND_AXE);
                    RefinementService.apply(weapon, 3 + random.nextInt(3));
                    items.add(weapon);
                }
            }
        }
        // Extra surprise rolls: more variety at higher rarity.
        int extras = switch (rarity) { case COMMON -> 1; case RARE -> 1 + random.nextInt(2); case EPIC -> 2 + random.nextInt(2); case LEGENDARY -> 3 + random.nextInt(2); };
        for (int i = 0; i < extras; i++) items.add(surprise(player, rarity));
        if (rarity == LootRarity.EPIC && random.nextFloat() < 0.45F) items.add(refinedArmor(random, false, 2 + random.nextInt(2)));
        if (rarity == LootRarity.LEGENDARY) items.add(refinedArmor(random, true, 4 + random.nextInt(2)));
        return items;
    }

    private static final Item[][] ARMOR = {
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}};

    /** A refined armor piece: extra armor and toughness from the same refinement system as weapons. */
    private static ItemStack refinedArmor(net.minecraft.util.RandomSource random, boolean diamond, int level) {
        var piece = new ItemStack(ARMOR[diamond || random.nextFloat() < 0.4F ? 1 : 0][random.nextInt(4)]);
        return RefinementService.apply(piece, level);
    }

    /** Weighted surprise pool; weights shift toward valuable entries with rarity. */
    private static ItemStack surprise(ServerPlayer player, LootRarity rarity) {
        var random = player.getRandom(); int tier = rarity.ordinal();
        int roll = random.nextInt(100) + tier * 12;
        if (roll < 12) return stack(Items.ARROW, 16 + random.nextInt(17));
        if (roll < 22) return stack(Items.COOKED_BEEF, 6 + random.nextInt(7));
        if (roll < 30) return stack(Items.LAPIS_LAZULI, 8 + random.nextInt(9));
        if (roll < 38) return stack(Items.REDSTONE, 12 + random.nextInt(13));
        if (roll < 45) return stack(Items.GOLDEN_CARROT, 4 + random.nextInt(5));
        if (roll < 52) return stack(Items.FIREWORK_ROCKET, 6 + random.nextInt(7));
        if (roll < 58) return stack(Items.OBSIDIAN, 4 + random.nextInt(5));
        if (roll < 64) return stack(Items.ENDER_PEARL, 2 + random.nextInt(3));
        if (roll < 70) return net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
                random.nextBoolean() ? net.minecraft.world.item.alchemy.Potions.STRONG_HEALING : net.minecraft.world.item.alchemy.Potions.LONG_STRENGTH);
        if (roll < 75) return stack(Items.NAME_TAG, 1);
        if (roll < 80) return stack(Items.SADDLE, 1);
        if (roll < 86) return refinedArmor(random, false, 1 + random.nextInt(2));
        if (roll < 92) return stack(Items.BLAZE_ROD, 3 + random.nextInt(4));
        if (roll < 100) return new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 1 + random.nextInt(2));
        if (roll < 110) return KingdomTomes.roll(player.serverLevel(), random);
        if (roll < 120) return stack(Items.DIAMOND, 1 + random.nextInt(3));
        if (roll < 128) return stack(Items.EMERALD_BLOCK, 1 + random.nextInt(2));
        if (roll < 134) return stack(Items.NETHERITE_SCRAP, 1);
        return random.nextFloat() < 0.5F ? stack(Items.TOTEM_OF_UNDYING, 1) : stack(Items.ENCHANTED_GOLDEN_APPLE, 1);
    }

    private static ItemStack stack(Item item, int count) { return new ItemStack(item, count); }
}

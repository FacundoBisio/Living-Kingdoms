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
        return items;
    }

    private static ItemStack stack(Item item, int count) { return new ItemStack(item, count); }
}

package dev.livingkingdoms.rpg;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import java.util.List;

/** Curated enchanted-book rewards. Weighted toward useful mid-level books; Mending stays rare. */
public final class KingdomTomes {
    private record Entry(ResourceKey<Enchantment> key, int level, int weight) {}
    private static final List<Entry> POOL = List.of(
            new Entry(Enchantments.SHARPNESS, 3, 10), new Entry(Enchantments.PROTECTION, 3, 10),
            new Entry(Enchantments.EFFICIENCY, 3, 8), new Entry(Enchantments.UNBREAKING, 3, 8),
            new Entry(Enchantments.POWER, 3, 6), new Entry(Enchantments.LOOTING, 2, 5),
            new Entry(Enchantments.FORTUNE, 2, 5), new Entry(Enchantments.FEATHER_FALLING, 3, 5),
            new Entry(Enchantments.SWEEPING_EDGE, 2, 4), new Entry(Enchantments.MENDING, 1, 2));
    private KingdomTomes() {}

    public static ItemStack roll(ServerLevel level, RandomSource random) {
        int total = POOL.stream().mapToInt(Entry::weight).sum();
        int pick = random.nextInt(total);
        Entry chosen = POOL.getLast();
        for (var entry : POOL) { pick -= entry.weight(); if (pick < 0) { chosen = entry; break; } }
        var holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(chosen.key());
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, chosen.level()));
    }
}

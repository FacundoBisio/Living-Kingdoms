package dev.livingkingdoms.rpg.domain;

import java.util.Random;

/**
 * Chest rarity. {@link #roll} uses base weights 70/24/5/1 and shifts weight up the ladder with luck
 * (kingdom level, streak, source quality), so better play visibly improves the odds without guarantees.
 */
public enum LootRarity {
    COMMON(0xFFB8B8B8), RARE(0xFF4FA3E0), EPIC(0xFFB05CE8), LEGENDARY(0xFFF2B33D);

    private final int color;
    LootRarity(int color) { this.color = color; }
    public int color() { return color; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static LootRarity roll(Random random, int luck, LootRarity minimum) {
        int l = Math.max(0, Math.min(40, luck));
        int common = Math.max(10, 70 - l * 2), rare = 24 + l, epic = 5 + l / 2 + l / 4, legendary = 1 + l / 6;
        int pick = random.nextInt(common + rare + epic + legendary);
        LootRarity result = pick < common ? COMMON : pick < common + rare ? RARE : pick < common + rare + epic ? EPIC : LEGENDARY;
        return result.ordinal() < minimum.ordinal() ? minimum : result;
    }

    public static LootRarity fromId(String id) {
        for (var rarity : values()) if (rarity.id().equals(id)) return rarity;
        throw new IllegalArgumentException("Unknown rarity: " + id);
    }
}

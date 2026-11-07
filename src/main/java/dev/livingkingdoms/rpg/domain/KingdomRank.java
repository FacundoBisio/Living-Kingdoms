package dev.livingkingdoms.rpg.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared kingdom progression. XP thresholds grow triangularly (0, 100, 300, 600, 1000, ...) so early levels
 * arrive within the first sessions and later ones take weeks. Every level grants a loot chest; some levels
 * also unlock a permanent perk, listed by {@link #unlocks(int)}.
 */
public final class KingdomRank {
    public static final int MAX_LEVEL = 20;
    public static final long MAX_XP = 10_000_000;
    public static final int CONSTRUCTION_XP = 60, TRIAL_XP_PER_RANK = 100;
    private KingdomRank() {}

    /** Total XP needed to reach {@code level} (level 1 needs 0). */
    public static long threshold(int level) {
        int n = Math.max(1, Math.min(MAX_LEVEL, level)) - 1;
        return 50L * n * (n + 1);
    }

    public static int level(long xp) {
        int level = 1;
        while (level < MAX_LEVEL && xp >= threshold(level + 1)) level++;
        return level;
    }

    /** XP inside the current level and the size of the current level, for progress bars. */
    public static long progress(long xp) { return Math.max(0, xp - threshold(level(xp))); }
    public static long span(long xp) {
        int level = level(xp);
        return level >= MAX_LEVEL ? 1 : threshold(level + 1) - threshold(level);
    }

    public static int dailyContracts(int level) { return level >= 4 ? 4 : 3; }
    /** Luck added to every chest rarity roll for members of this kingdom. */
    public static int chestLuck(int level) { return Math.max(0, Math.min(MAX_LEVEL, level) - 1); }
    /** Percent bonus on quest/contract emeralds. */
    public static int emeraldBonusPercent(int level) { return level >= 8 ? 20 : level >= 6 ? 10 : 0; }

    public static LootRarity levelUpChest(int level) {
        if (level >= MAX_LEVEL) return LootRarity.LEGENDARY;
        if (level % 5 == 0 || level >= 10) return LootRarity.EPIC;
        return LootRarity.RARE;
    }

    /** Localization suffixes of the perks first available at exactly this level. */
    public static List<String> unlocks(int level) {
        var keys = new ArrayList<String>();
        if (level == 2) keys.add("weekly");
        if (level == 4) keys.add("fourth_daily");
        if (level == 6) keys.add("emeralds_10");
        if (level == 8) keys.add("emeralds_20");
        if (level % 5 == 0) keys.add("epic_chest");
        return keys;
    }

    public static boolean weeklyUnlocked(int level) { return level >= 2; }
}

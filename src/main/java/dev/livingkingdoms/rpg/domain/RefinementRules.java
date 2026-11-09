package dev.livingkingdoms.rpg.domain;

/**
 * Pure weapon refinement math. Five refinement levels add +0.4 attack damage each, so a vanilla
 * diamond sword (7) reaches 9 at the cap. Each step costs one Royal Whetstone and an increasing
 * amount of player experience levels, which keeps village rewards meaningful without replacing
 * enchanting.
 */
public final class RefinementRules {
    public static final int MAX_LEVEL = 5;
    public static final double DAMAGE_PER_LEVEL = 0.4;
    /** Armor refinement: +0.5 armor and +0.25 toughness per level (+2.5 / +1.25 at the cap). */
    public static final double ARMOR_PER_LEVEL = 0.5, TOUGHNESS_PER_LEVEL = 0.25;
    private RefinementRules() {}

    public static double damageBonus(int level) {
        return clamp(level) * DAMAGE_PER_LEVEL;
    }

    /** Experience levels charged to go from {@code current} to {@code current + 1}. */
    public static double armorBonus(int level) { return clamp(level) * ARMOR_PER_LEVEL; }
    public static double toughnessBonus(int level) { return clamp(level) * TOUGHNESS_PER_LEVEL; }

    public static int experienceCost(int current) {
        if (current < 0 || current >= MAX_LEVEL) throw new IllegalArgumentException("No refinement step from " + current);
        return 1 + current;
    }

    public static boolean canRefine(int current) {
        return current >= 0 && current < MAX_LEVEL;
    }

    public static int clamp(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }
}

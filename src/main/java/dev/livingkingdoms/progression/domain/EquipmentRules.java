package dev.livingkingdoms.progression.domain;

import dev.livingkingdoms.faction.Faction;

public record EquipmentRules(int lightLevel, int standardLevel, int advancedLevel, int enchantLevel, double enchantChance) {
    public EquipmentRules {
        if (lightLevel < 1 || standardLevel < lightLevel || advancedLevel < standardLevel
                || advancedLevel > LevelValue.MAX_SUPPORTED || enchantLevel < 1 || enchantLevel > LevelValue.MAX_SUPPORTED
                || !Double.isFinite(enchantChance) || enchantChance < 0 || enchantChance > 1) throw new IllegalArgumentException("Invalid equipment thresholds");
    }
    public static EquipmentRules defaults() { return new EquipmentRules(5, 10, 20, 15, .08); }
    public Tier tier(Faction faction, LevelValue level) {
        if (faction.isAllied()) return Tier.BASIC;
        return level.value() >= advancedLevel ? Tier.IRON : level.value() >= standardLevel ? Tier.CHAIN
                : level.value() >= lightLevel ? Tier.LEATHER : Tier.BASIC;
    }
    public boolean enchant(LevelValue level, double roll) { validRoll(roll); return level.value() >= enchantLevel && roll < enchantChance; }
    public static double validRoll(double roll) {
        if (!Double.isFinite(roll) || roll < 0 || roll >= 1) throw new IllegalArgumentException("Random roll must be 0..1 exclusive");
        return roll;
    }
    public enum Tier { BASIC, LEATHER, CHAIN, IRON }
}

package dev.livingkingdoms.progression.domain;

public record RegionalRules(int maximumLevel, int baseLevel, int blocksPerBand, int levelsPerBand,
                            int maximumDistanceBonus, int daysPerLevel, int maximumAgeBonus,
                            int activityPerLevel, int maximumActivityBonus, int levelsPerHostileTier,
                            int maximumHostileTierBonus, int memberVariance) {
    public RegionalRules {
        if (maximumLevel < 1 || maximumLevel > LevelValue.MAX_SUPPORTED || baseLevel < 1 || baseLevel > LevelValue.MAX_SUPPORTED
                || blocksPerBand < 1 || daysPerLevel < 1 || activityPerLevel < 1 || levelsPerBand < 0
                || maximumDistanceBonus < 0 || maximumAgeBonus < 0 || maximumActivityBonus < 0
                || levelsPerHostileTier < 0 || maximumHostileTierBonus < 0 || memberVariance < 0 || memberVariance > 10) {
            throw new IllegalArgumentException("Invalid regional progression rules");
        }
    }
    public static RegionalRules defaults() { return new RegionalRules(30, 1, 1024, 1, 24, 10, 3, 4, 2, 2, 10, 2); }
}

package dev.livingkingdoms.progression.domain;

import java.util.Objects;

/** Deterministic, inspectable regional result. Future quests can reuse the individual contributions. */
public record RegionalDifficulty(LevelValue level, int distanceBonus, int ageBonus, int activityBonus, int hostileTierBonus) {
    public RegionalDifficulty { Objects.requireNonNull(level); }
    public static RegionalDifficulty calculate(double distance, long worldDays, int hostileActivity, int hostileTier, RegionalRules rules) {
        Objects.requireNonNull(rules);
        if (!Double.isFinite(distance) || distance < 0 || worldDays < 0 || hostileActivity < 0 || hostileTier < 0) {
            throw new IllegalArgumentException("Invalid regional inputs");
        }
        int distanceBonus = (int) Math.min(rules.maximumDistanceBonus(), Math.floor(distance / rules.blocksPerBand()) * rules.levelsPerBand());
        int ageBonus = (int) Math.min(rules.maximumAgeBonus(), worldDays / rules.daysPerLevel());
        int activityBonus = Math.min(rules.maximumActivityBonus(), hostileActivity / rules.activityPerLevel());
        int tierBonus = (int) Math.min(rules.maximumHostileTierBonus(), (long) hostileTier * rules.levelsPerHostileTier());
        int total = (int) Math.min(rules.maximumLevel(), (long) rules.baseLevel() + distanceBonus + ageBonus + activityBonus + tierBonus);
        return new RegionalDifficulty(new LevelValue(total), distanceBonus, ageBonus, activityBonus, tierBonus);
    }
    /** The caller supplies a random offset, keeping this policy deterministic and testable. */
    public LevelValue memberLevel(RegionalRules rules, int offset) {
        if (Math.abs((long) offset) > rules.memberVariance()) throw new IllegalArgumentException("Invalid member level offset");
        return new LevelValue(Math.clamp((long) level.value() + offset, 1, rules.maximumLevel()));
    }
}

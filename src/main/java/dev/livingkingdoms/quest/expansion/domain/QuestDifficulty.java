package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.progression.domain.LevelValue;
import java.util.Objects;

public enum QuestDifficulty {
    VERY_EASY, EASY, NORMAL, HARD, VERY_HARD;
    public static QuestDifficulty fromLevel(LevelValue level) {
        Objects.requireNonNull(level, "level");
        int value = level.value();
        if (value <= 3) return VERY_EASY;
        if (value <= 7) return EASY;
        if (value <= 12) return NORMAL;
        if (value <= 20) return HARD;
        return VERY_HARD;
    }
    /** Explicit reward tier, independent of enum persistence representation. */
    public int rewardSteps() {
        return switch (this) { case VERY_EASY -> 0; case EASY -> 1; case NORMAL -> 2; case HARD -> 3; case VERY_HARD -> 4; };
    }
    public String titleKey() { return "quest.livingkingdoms.difficulty." + name().toLowerCase(java.util.Locale.ROOT); }
}

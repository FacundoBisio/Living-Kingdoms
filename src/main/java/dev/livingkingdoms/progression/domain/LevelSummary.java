package dev.livingkingdoms.progression.domain;

import java.util.List;

/** Spawn roster snapshot, retained after deaths; entities own their individual levels. */
public record LevelSummary(int count, int sum, int minimum, int maximum) {
    public LevelSummary {
        if (count < 1 || count > 16 || minimum < 1 || maximum > LevelValue.MAX_SUPPORTED || maximum < minimum
                || sum < count * minimum || sum > count * maximum) throw new IllegalArgumentException("Invalid party level summary");
        if ((count == 1 && minimum != maximum) || sum < maximum + (count - 1) * minimum
                || sum > minimum + (count - 1) * maximum) throw new IllegalArgumentException("Inconsistent party level extremes");
    }
    public double average() { return (double) sum / count; }
    public static LevelSummary uniform(int count, int level) { return new LevelSummary(count, count * level, level, level); }
    public static LevelSummary of(List<LevelValue> levels) {
        return new LevelSummary(levels.size(), levels.stream().mapToInt(LevelValue::value).sum(),
                levels.stream().mapToInt(LevelValue::value).min().orElseThrow(), levels.stream().mapToInt(LevelValue::value).max().orElseThrow());
    }
}

package dev.livingkingdoms.progression.domain;

/** Shared level identity; combat and civilian progression policies remain separate. */
public record LevelValue(int value) {
    public static final int MAX_SUPPORTED = 100;
    public LevelValue {
        if (value < 1 || value > MAX_SUPPORTED) throw new IllegalArgumentException("Level must be 1..100");
    }
}

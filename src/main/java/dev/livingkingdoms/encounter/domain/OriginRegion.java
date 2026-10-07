package dev.livingkingdoms.encounter.domain;

import java.util.Objects;

/** Approximate encounter origin, not a claim or an entity tracking radius. */
public record OriginRegion(String dimension, int x, int y, int z, int radius) {
    public OriginRegion {
        Objects.requireNonNull(dimension, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid dimension identifier: " + dimension);
        }
        if (radius < 1 || radius > 4096) {
            throw new IllegalArgumentException("Origin radius must be between 1 and 4096");
        }
    }
}

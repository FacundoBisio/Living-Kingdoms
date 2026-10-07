package dev.livingkingdoms.settlement.domain;

import java.util.Objects;

/** A dimension-scoped horizontal circle. Y stores the founding point, not a height limit. */
public record Territory(String dimension, int x, int y, int z, int radius) {
    public Territory {
        Objects.requireNonNull(dimension, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid dimension identifier: " + dimension);
        }
        if (radius < 1) throw new IllegalArgumentException("Territory radius must be positive");
    }

    public boolean contains(String dimension, int x, int z) {
        return this.dimension.equals(dimension) && distanceSquared(x, z) <= (double) radius * radius;
    }

    public boolean overlaps(Territory other) {
        double combinedRadius = (double) radius + other.radius;
        return dimension.equals(other.dimension)
                && distanceSquared(other.x, other.z) <= combinedRadius * combinedRadius;
    }

    private double distanceSquared(int x, int z) {
        double dx = (double) this.x - x;
        double dz = (double) this.z - z;
        return dx * dx + dz * dz;
    }
}

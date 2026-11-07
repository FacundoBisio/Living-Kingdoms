package dev.livingkingdoms.rpg.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A temporary call that pulls residents of one settlement to a point. It is transient by design: a
 * restart simply releases everyone back to their routine, so nothing is persisted.
 */
public final class RallyPolicy {
    public static final long DEFAULT_DURATION_TICKS = 1200;
    public static final double ARRIVAL_DISTANCE_SQ = 6.25;
    public static final double MAX_CALL_DISTANCE = 96;
    private RallyPolicy() {}

    /** {@code citizen == null} calls every resident; otherwise only that citizen answers. */
    public record Rally(String dimension, int x, int y, int z, long until, UUID citizen) {
        public Rally {
            Objects.requireNonNull(dimension, "dimension");
            if (until < 0) throw new IllegalArgumentException("Invalid rally deadline");
        }
        public boolean active(long now) { return now < until; }
        public boolean applies(UUID candidate) { return citizen == null || citizen.equals(candidate); }
    }

    public static Rally call(String dimension, int x, int y, int z, long now, long duration, UUID citizen) {
        if (duration < 20 || duration > 24000) throw new IllegalArgumentException("Rally duration out of range");
        return new Rally(dimension, x, y, z, Math.addExact(now, duration), citizen);
    }

    public static Optional<Rally> effective(Rally rally, long now, UUID candidate, String dimension) {
        if (rally == null || !rally.active(now) || !rally.applies(candidate) || !rally.dimension().equals(dimension)) return Optional.empty();
        return Optional.of(rally);
    }

    public static boolean withinReach(double dx, double dz) {
        return dx * dx + dz * dz <= MAX_CALL_DISTANCE * MAX_CALL_DISTANCE;
    }
}

package dev.livingkingdoms.war.domain;

import java.util.Objects;
import java.util.UUID;

/** Pure, deterministic war rules. No world, entity or chunk access. */
public final class WarPolicy {
    public static final int MAX_WARBAND_SIZE = 8;
    private WarPolicy() {}

    /** Seeded, stable stance for one kingdom: the same world always produces the same enemies. */
    public static KingdomStance stance(long seed, UUID kingdom, double hostileChance) {
        Objects.requireNonNull(kingdom, "kingdom");
        if (!(hostileChance >= 0 && hostileChance <= 1)) throw new IllegalArgumentException("Hostile chance must be 0..1");
        long bits = mix(seed ^ kingdom.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ kingdom.getLeastSignificantBits());
        double roll = (bits >>> 11) * 0x1.0p-53;
        return roll < hostileChance ? KingdomStance.HOSTILE : KingdomStance.INDEPENDENT;
    }

    /** One extra soldier every two strength levels, never beyond the spawn formation. */
    public static int warbandSize(int baseSize, int strength) {
        requireStrength(strength);
        if (baseSize < 2 || baseSize > MAX_WARBAND_SIZE) throw new IllegalArgumentException("Invalid warband base size");
        return Math.min(MAX_WARBAND_SIZE, baseSize + (strength - 1) / 2);
    }

    /** Stronger kingdoms raid more often, down to half the configured cooldown at maximum strength. */
    public static int raidCooldown(int baseCooldown, int strength) {
        requireStrength(strength);
        if (baseCooldown < 1) throw new IllegalArgumentException("Invalid raid cooldown");
        long reduced = (long) baseCooldown - (long) baseCooldown * (strength - 1) / 18;
        return (int) Math.max((baseCooldown + 1) / 2, reduced);
    }

    /** Horizontal bearing in radians from one point to another. */
    public static double bearing(double fromX, double fromZ, double toX, double toZ) {
        return Math.atan2(toZ - fromZ, toX - fromX);
    }

    /**
     * Eight candidate directions, starting at the bearing and alternating outwards, so a warband appears on
     * the side of the kingdom it marched from whenever that terrain is usable.
     */
    public static double[] approachAngles(double bearing) {
        if (!Double.isFinite(bearing)) throw new IllegalArgumentException("Invalid bearing");
        double[] angles = new double[8];
        angles[0] = bearing;
        int index = 1;
        for (int step = 1; step <= 3; step++) {
            angles[index++] = bearing + step * Math.PI / 4;
            angles[index++] = bearing - step * Math.PI / 4;
        }
        angles[7] = bearing + Math.PI;
        return angles;
    }

    public static boolean withinRange(long dx, long dz, int range) {
        if (range < 0) throw new IllegalArgumentException("Range cannot be negative");
        if (Math.abs(dx) > range || Math.abs(dz) > range) return false;
        return dx * dx + dz * dz <= (long) range * range;
    }

    private static void requireStrength(int strength) {
        if (strength < WarFront.MIN_STRENGTH || strength > WarFront.MAX_STRENGTH) throw new IllegalArgumentException("Invalid war strength");
    }

    /** SplitMix64 finalizer. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}

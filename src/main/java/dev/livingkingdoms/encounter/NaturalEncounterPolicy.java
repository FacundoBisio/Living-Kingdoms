package dev.livingkingdoms.encounter;

import dev.livingkingdoms.encounter.domain.PartyType;

/** Small deterministic rules shared by natural proposals and their unit tests. */
public final class NaturalEncounterPolicy {
    private NaturalEncounterPolicy() {}

    public static boolean canAttempt(boolean enabled, boolean mobSpawning, boolean peaceful,
                                     boolean overworld, long now, long nextAttempt) {
        return enabled && mobSpawning && !peaceful && overworld && now >= nextAttempt;
    }

    public static boolean hasCapacity(int regionalCount, int regionalLimit, int trackedCount, int trackedLimit) {
        return regionalCount < regionalLimit && trackedCount < trackedLimit;
    }

    /** Horizontal origin spacing, inclusive at the configured minimum. Handles extreme coordinates. */
    public static boolean separated(int x, int z, int otherX, int otherZ, int minimumDistance) {
        if (minimumDistance < 0) throw new IllegalArgumentException("Minimum spacing cannot be negative");
        long dx = (long) otherX - x;
        long dz = (long) otherZ - z;
        if (Math.abs(dx) >= minimumDistance || Math.abs(dz) >= minimumDistance) return true;
        return dx * dx + dz * dz >= (long) minimumDistance * minimumDistance;
    }

    /** Three out of four nighttime proposals favor Undead; daytime proposals are Pillagers. */
    public static PartyType typeForTime(boolean night, int roll) {
        if (roll < 0 || roll > 3) throw new IllegalArgumentException("Encounter roll must be 0 through 3");
        return night && roll < 3 ? PartyType.UNDEAD_HORDE : PartyType.PILLAGER_PATROL;
    }

    /**
     * Bandits roam day and night: {@code banditRoll} below {@code banditChance} selects them, otherwise the
     * day/night split of {@link #typeForTime(boolean, int)} applies to the remaining probability.
     */
    public static PartyType typeForTime(boolean night, int roll, double banditChance, double banditRoll) {
        if (!(banditChance >= 0.0 && banditChance <= 1.0)) throw new IllegalArgumentException("Bandit chance must be 0 through 1");
        if (!(banditRoll >= 0.0 && banditRoll < 1.0)) throw new IllegalArgumentException("Bandit roll must be in [0, 1)");
        PartyType fallback = typeForTime(night, roll);
        return banditRoll < banditChance ? PartyType.BANDIT_PARTY : fallback;
    }

    public static boolean undeadAllowed(boolean night, int blockLight) {
        return night && blockLight >= 0 && blockLight <= 7;
    }

    public static long nextAttempt(long now, int cooldown) {
        if (now < 0 || cooldown < 1) throw new IllegalArgumentException("Invalid natural encounter clock");
        return now > Long.MAX_VALUE - cooldown ? Long.MAX_VALUE : now + cooldown;
    }
}

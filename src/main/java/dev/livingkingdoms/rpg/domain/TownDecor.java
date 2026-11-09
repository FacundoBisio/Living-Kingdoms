package dev.livingkingdoms.rpg.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Visible town upgrades unlocked by kingdom rank. Each tier is applied once per settlement (tracked as a
 * bitmask) and only ever places blocks into empty, supported, loaded spots outside buildings and paths.
 */
public enum TownDecor {
    LANTERNS(2), FLOWERS(3), BANNERS(4), FOUNTAIN(5), BENCHES(6), BRAZIERS(7), HEDGES(8), STATUE(10);

    private final int rank;
    TownDecor(int rank) { this.rank = rank; }
    public int rank() { return rank; }
    public int bit() { return 1 << ordinal(); }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    /** Tiers first unlocked exactly at this rank (for the rank-up splash). */
    public static List<TownDecor> tiersAt(int rank) {
        var result = new ArrayList<TownDecor>();
        for (var tier : values()) if (tier.rank == rank) result.add(tier);
        return result;
    }

    /** Tiers unlocked at or below {@code rank} that are not yet in {@code applied}. */
    public static List<TownDecor> pending(int rank, int applied) {
        var result = new ArrayList<TownDecor>();
        for (var tier : values()) if (tier.rank <= rank && (applied & tier.bit()) == 0) result.add(tier);
        return result;
    }
}

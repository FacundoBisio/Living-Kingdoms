package dev.livingkingdoms.rpg.domain;

/**
 * Named growth stages of a kingdom, derived from its rank (never stored): Hamlet → Village → Town → City.
 * Each stage-up is celebrated and visibly upgrades every house (see {@link TownDecor} home tiers).
 */
public enum KingdomStage {
    HAMLET(1), VILLAGE(3), TOWN(6), CITY(9);
    private final int rank;
    KingdomStage(int rank) { this.rank = rank; }
    public int rank() { return rank; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static KingdomStage of(int rank) {
        var result = HAMLET;
        for (var stage : values()) if (rank >= stage.rank) result = stage;
        return result;
    }
    /** The stage first reached exactly at this rank, if any. */
    public static java.util.Optional<KingdomStage> reachedAt(int rank) {
        for (var stage : values()) if (stage.rank == rank && stage != HAMLET) return java.util.Optional.of(stage);
        return java.util.Optional.empty();
    }
    public java.util.Optional<KingdomStage> next() {
        return ordinal() + 1 < values().length ? java.util.Optional.of(values()[ordinal() + 1]) : java.util.Optional.empty();
    }
}

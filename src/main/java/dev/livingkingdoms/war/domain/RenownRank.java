package dev.livingkingdoms.war.domain;

/** Player military ranks earned by defeating real hostile parties. Thresholds are cumulative renown. */
public enum RenownRank {
    RECRUIT("recruit", 0),
    SQUIRE("squire", 40),
    SERGEANT("sergeant", 120),
    KNIGHT("knight", 300),
    CAPTAIN("captain", 700),
    WARLORD("warlord", 1500);

    private final String id;
    private final int threshold;

    RenownRank(String id, int threshold) { this.id = id; this.threshold = threshold; }

    public String id() { return id; }
    public int threshold() { return threshold; }
    public String titleKey() { return "renown.livingkingdoms.rank." + id; }

    public static RenownRank of(int points) {
        if (points < 0) throw new IllegalArgumentException("Renown cannot be negative");
        RenownRank result = RECRUIT;
        for (RenownRank rank : values()) if (points >= rank.threshold) result = rank;
        return result;
    }

    /** Empty at the highest rank. */
    public java.util.Optional<RenownRank> next() {
        return ordinal() + 1 < values().length ? java.util.Optional.of(values()[ordinal() + 1]) : java.util.Optional.empty();
    }

    public static RenownRank fromId(String id) {
        for (RenownRank rank : values()) if (rank.id.equals(id)) return rank;
        throw new IllegalArgumentException("Unknown renown rank: " + id);
    }
}

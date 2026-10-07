package dev.livingkingdoms.settlement.domain;

/** Stable persistence identifiers, independent of presentation and conquest state. */
public enum Faction {
    ALLIED("allied"),
    PILLAGER("pillager");

    private final String id;

    Faction(String id) { this.id = id; }

    public String id() { return id; }

    public static Faction fromId(String id) {
        for (Faction faction : values()) {
            if (faction.id.equals(id)) return faction;
        }
        throw new IllegalArgumentException("Unknown settlement faction: " + id);
    }
}

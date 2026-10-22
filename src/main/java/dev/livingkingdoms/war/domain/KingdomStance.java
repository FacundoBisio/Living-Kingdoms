package dev.livingkingdoms.war.domain;

/** How an NPC kingdom treats the player kingdoms around it. Persisted by id. */
public enum KingdomStance {
    INDEPENDENT("independent"),
    HOSTILE("hostile");

    private final String id;

    KingdomStance(String id) { this.id = id; }

    public String id() { return id; }

    public static KingdomStance fromId(String id) {
        for (KingdomStance stance : values()) if (stance.id.equals(id)) return stance;
        throw new IllegalArgumentException("Unknown kingdom stance: " + id);
    }
}

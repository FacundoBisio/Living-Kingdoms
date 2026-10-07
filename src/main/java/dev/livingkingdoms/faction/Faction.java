package dev.livingkingdoms.faction;

/** Stable faction identities shared by settlements, encounters and future combat policies. */
public enum Faction {
    ALLIED_KINGDOM("allied_kingdom", true),
    PILLAGER("pillager", false),
    BANDIT("bandit", false),
    UNDEAD("undead", false);

    /** Source compatibility for the first milestones; persisted legacy "allied" is also accepted. */
    @Deprecated
    public static final Faction ALLIED = ALLIED_KINGDOM;

    private final String id;
    private final boolean allied;

    Faction(String id, boolean allied) {
        this.id = id;
        this.allied = allied;
    }

    public String id() { return id; }

    public boolean isAllied() { return allied; }

    public static Faction fromId(String id) {
        if ("allied".equals(id)) return ALLIED_KINGDOM;
        for (Faction faction : values()) {
            if (faction.id.equals(id)) return faction;
        }
        throw new IllegalArgumentException("Unknown faction: " + id);
    }
}

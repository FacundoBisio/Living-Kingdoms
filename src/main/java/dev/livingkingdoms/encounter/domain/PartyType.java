package dev.livingkingdoms.encounter.domain;

import dev.livingkingdoms.faction.Faction;

/** Content identities; threat and reward are supplied by server configuration at spawn. */
public enum PartyType {
    PILLAGER_PATROL("pillager_patrol", Faction.PILLAGER),
    UNDEAD_HORDE("undead_horde", Faction.UNDEAD);

    private final String id;
    private final Faction faction;

    PartyType(String id, Faction faction) {
        this.id = id;
        this.faction = faction;
    }

    public String id() {
        return id;
    }

    public Faction faction() {
        return faction;
    }

    public static PartyType fromId(String id) {
        for (PartyType type : values()) {
            if (type.id.equals(id)) return type;
        }
        throw new IllegalArgumentException("Unknown Living Kingdoms party type: " + id);
    }
}

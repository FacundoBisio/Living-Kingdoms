package dev.livingkingdoms.settlement.domain;

import dev.livingkingdoms.faction.Faction;

import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate: future changes must replace it through server-owned storage. */
public record Settlement(UUID id, String name, Faction faction, int level, int population,
                         Territory territory) {
    public Settlement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(territory, "territory");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Settlement name must contain 1 to 128 characters");
        }
        if (level < 1) throw new IllegalArgumentException("Settlement level must be positive");
        if (population < 0) throw new IllegalArgumentException("Settlement population cannot be negative");
    }

    public static Settlement founding(UUID id, Territory territory, int population) {
        return new Settlement(id, "Haven " + id.toString().substring(0, 8), Faction.ALLIED_KINGDOM, 1,
                population, territory);
    }
}

package dev.livingkingdoms.settlement.domain;

import dev.livingkingdoms.faction.Faction;

import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate: future changes must replace it through server-owned storage. */
public record Settlement(UUID id, String name, Faction faction, int level, int population,
                         Territory territory, SettlementProvenance provenance, SettlementLifecycle lifecycle) {
    public Settlement(UUID id, String name, Faction faction, int level, int population,
                      Territory territory, SettlementProvenance provenance) {
        this(id, name, faction, level, population, territory, provenance, SettlementLifecycle.ESTABLISHED);
    }

    public Settlement withLifecycle(SettlementLifecycle next) {
        return new Settlement(id, name, faction, level, population, territory, provenance, next);
    }
    /** Source compatibility for old integrations and records with unknown provenance. */
    public Settlement(UUID id, String name, Faction faction, int level, int population, Territory territory) {
        this(id, name, faction, level, population, territory, SettlementProvenance.legacy());
    }

    public Settlement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(territory, "territory");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Settlement name must contain 1 to 128 characters");
        }
        if (level < 1) throw new IllegalArgumentException("Settlement level must be positive");
        if (population < 0) throw new IllegalArgumentException("Settlement population cannot be negative");
    }

    public static Settlement founding(UUID id, Territory territory, int population) {
        return new Settlement(id, dev.livingkingdoms.ui.VillageNames.generated(id), Faction.ALLIED_KINGDOM, 1,
                population, territory, SettlementProvenance.created(SettlementOrigin.GENERATED, null));
    }

    public static Settlement established(UUID id, Territory territory, int population, SettlementOrigin origin, UUID founder) {
        return new Settlement(id, dev.livingkingdoms.ui.VillageNames.generated(id), Faction.ALLIED_KINGDOM, 1,
                population, territory, SettlementProvenance.created(origin, Objects.requireNonNull(founder)));
    }
}

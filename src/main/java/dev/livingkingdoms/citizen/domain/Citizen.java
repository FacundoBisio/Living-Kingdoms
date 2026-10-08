package dev.livingkingdoms.citizen.domain;

import dev.livingkingdoms.progression.domain.LevelValue;

import java.util.Objects;
import java.util.UUID;

/** Persistent identity belongs to a citizen even while the vanilla entity is unloaded. */
public record Citizen(UUID id, UUID entityId, UUID settlementId, String name, LevelValue level,
                      CitizenRole role, UUID homeId, CitizenState state, long joinedAt) {
    public Citizen {
        Objects.requireNonNull(id); Objects.requireNonNull(entityId); Objects.requireNonNull(settlementId);
        Objects.requireNonNull(name); Objects.requireNonNull(level); Objects.requireNonNull(role); Objects.requireNonNull(state);
        if (name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid citizen name");
        if (joinedAt < 0) throw new IllegalArgumentException("Invalid citizen joining time");
        if (state != CitizenState.ACTIVE && homeId != null)
            throw new IllegalArgumentException("Inactive citizens cannot occupy housing");
    }

    public Citizen withHome(UUID home) {
        return new Citizen(id, entityId, settlementId, name, level, role, home, state, joinedAt);
    }

    public Citizen withState(CitizenState next) {
        return new Citizen(id, entityId, settlementId, name, level, role,
                next == CitizenState.ACTIVE ? homeId : null, next, joinedAt);
    }
}

package dev.livingkingdoms.citizen.domain;

import dev.livingkingdoms.progression.domain.LevelValue;

import java.util.Objects;
import java.util.UUID;

/** A shared settlement request. No entity exists until a player successfully accepts. */
public record ImmigrationCandidate(UUID id, UUID settlementId, String name, LevelValue level,
                                   CitizenRole preferredRole, long createdAt, long expiresAt) {
    public ImmigrationCandidate {
        Objects.requireNonNull(id); Objects.requireNonNull(settlementId); Objects.requireNonNull(name);
        Objects.requireNonNull(level); Objects.requireNonNull(preferredRole);
        if (name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid immigrant name");
        if (createdAt < 0 || expiresAt <= createdAt) throw new IllegalArgumentException("Invalid candidate lifetime");
    }

    public boolean expired(long now) { return now >= expiresAt; }
}

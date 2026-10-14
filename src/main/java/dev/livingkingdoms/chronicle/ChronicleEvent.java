package dev.livingkingdoms.chronicle;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable facts recorded after their authoritative gameplay transition has committed. */
public record ChronicleEvent(UUID id, UUID settlement, Kind kind, long tick, String receipt, String subject) {
    public enum Kind {
        CITIZEN_DIED, GUARD_DIED, ESSENTIAL_WORKPLACE_EMPTY, SIGNIFICANT_THREAT, CONSTRUCTION_COMPLETED, UPGRADE_COMPLETED;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    public ChronicleEvent {
        Objects.requireNonNull(id); Objects.requireNonNull(settlement); Objects.requireNonNull(kind);
        Objects.requireNonNull(receipt); Objects.requireNonNull(subject);
        if (tick < 0 || receipt.isBlank() || receipt.length() > 160 || subject.length() > 80
                || receipt.codePoints().anyMatch(Character::isISOControl) || subject.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid chronicle event");
    }
}

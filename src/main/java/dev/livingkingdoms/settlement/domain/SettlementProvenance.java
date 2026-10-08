package dev.livingkingdoms.settlement.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Historical metadata, independent of naming, population and permissions. Zero means unknown legacy time. */
public record SettlementProvenance(SettlementOrigin origin, Optional<UUID> founder,
                                   long createdAtEpochMillis, Optional<UUID> kingdom) {
    public SettlementProvenance {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(kingdom, "kingdom");
        if (createdAtEpochMillis < 0) throw new IllegalArgumentException("Negative creation timestamp");
        if ((origin == SettlementOrigin.FOUNDED || origin == SettlementOrigin.CONVERTED)
                && (founder.isEmpty() || createdAtEpochMillis == 0))
            throw new IllegalArgumentException("Player establishments need a founder and creation time");
    }

    public static SettlementProvenance legacy() {
        return new SettlementProvenance(SettlementOrigin.GENERATED, Optional.empty(), 0, Optional.empty());
    }

    public static SettlementProvenance created(SettlementOrigin origin, UUID founder) {
        return new SettlementProvenance(origin, Optional.ofNullable(founder), System.currentTimeMillis(), Optional.empty());
    }
}

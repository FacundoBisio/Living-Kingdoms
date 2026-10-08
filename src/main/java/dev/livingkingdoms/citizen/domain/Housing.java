package dev.livingkingdoms.citizen.domain;

import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Occupancy is derived from Citizen.homeId so a second roster cannot drift out of sync. */
public record Housing(UUID id, UUID settlementId, String dimension, ResourceLocation template,
                      BlockPos position, BlockPos entrance, int capacity, HousingStatus status) {
    public Housing {
        Objects.requireNonNull(id); Objects.requireNonNull(settlementId); Objects.requireNonNull(template);
        Objects.requireNonNull(position); Objects.requireNonNull(entrance); Objects.requireNonNull(status);
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid housing dimension");
        if (capacity < 1 || capacity > 128) throw new IllegalArgumentException("Housing capacity must be 1..128");
        position = position.immutable(); entrance = entrance.immutable();
    }

    /** Stable across metadata reordering, save/reload, and later added buildings. */
    public static UUID identity(UUID settlementId, SettlementLayoutMetadata.Building building) {
        BlockPos p = building.origin();
        String key = settlementId + "|" + building.template() + "|" + p.getX() + "," + p.getY() + "," + p.getZ()
                + "|" + building.rotation().name();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}

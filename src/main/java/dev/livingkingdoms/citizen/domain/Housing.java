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
    public static final ResourceLocation CONVERTED_BED_TEMPLATE=ResourceLocation.parse("livingkingdoms:converted/vanilla_bed");
    public Housing {
        Objects.requireNonNull(id); Objects.requireNonNull(settlementId); Objects.requireNonNull(template);
        Objects.requireNonNull(position); Objects.requireNonNull(entrance); Objects.requireNonNull(status);
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid housing dimension");
        if (capacity < 1 || capacity > 128) throw new IllegalArgumentException("Housing capacity must be 1..128");
        if(template.equals(CONVERTED_BED_TEMPLATE) && (capacity!=1 || !id.equals(convertedBedIdentity(settlementId,position))))
            throw new IllegalArgumentException("Invalid converted bed home");
        position = position.immutable(); entrance = entrance.immutable();
    }
    public boolean convertedBed() { return template.equals(CONVERTED_BED_TEMPLATE); }
    public static UUID convertedBedIdentity(UUID settlement,BlockPos head) {
        return UUID.nameUUIDFromBytes((settlement+"|vanilla-bed|"+head.getX()+","+head.getY()+","+head.getZ()).getBytes(StandardCharsets.UTF_8));
    }

    /** Stable across metadata reordering, save/reload, and later added buildings. */
    public static UUID identity(UUID settlementId, SettlementLayoutMetadata.Building building) {
        BlockPos p = building.origin();
        String key = settlementId + "|" + building.template() + "|" + p.getX() + "," + p.getY() + "," + p.getZ()
                + "|" + building.rotation().name();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}

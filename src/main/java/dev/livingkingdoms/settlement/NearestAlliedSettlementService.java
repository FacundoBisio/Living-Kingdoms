package dev.livingkingdoms.settlement;

import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server event boundary for regional attribution. Neither method loads chunks. */
public final class NearestAlliedSettlementService {
    private NearestAlliedSettlementService() {}

    public static Optional<Settlement> findNearest(ServerLevel level, BlockPos position, int maxDistance) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        return SettlementSavedData.get(level.getServer()).nearestAllied(
                level.dimension().location().toString(), position.getX(), position.getZ(), maxDistance);
    }

    /** Resolves a previously associated region while rejecting hostile or other-dimension records. */
    public static Optional<Settlement> findAlliedById(ServerLevel level, UUID settlementId) {
        Objects.requireNonNull(level, "level");
        return SettlementSavedData.get(level.getServer()).get(settlementId)
                .filter(settlement -> settlement.faction().isAllied()
                        && settlement.territory().dimension().equals(level.dimension().location().toString()));
    }
}

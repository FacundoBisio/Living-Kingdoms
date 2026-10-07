package dev.livingkingdoms.structure;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

/** Read-only planning. Rejects unsafe sites before terrain or saved data changes. */
public final class SettlementSitePlanner {
    private static final TagKey<Block> GROUND = tag("settlement_ground");
    private static final TagKey<Block> CLEARABLE = tag("settlement_clearable");
    private static final int SEARCH_STEP = 16;
    private static final int FIRST_DISTANCE = 32;
    private static final int[][] DIRECTIONS = {{1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {-1,1}, {-1,-1}, {1,-1}};

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, name));
    }

    public Optional<Plan> findNear(ServerLevel level, SettlementSavedData data, SettlementTemplate template,
                                    BlockPos player, int searchRange, int maxSlope, int radius) {
        for (int distance = FIRST_DISTANCE; distance <= searchRange; distance += SEARCH_STEP) {
            for (int[] direction : DIRECTIONS) {
                // Diagonal candidates must also remain inside the configured search circle.
                if (Math.hypot(direction[0] * distance, direction[1] * distance) > searchRange) continue;
                var plan = at(level, data, template, player.offset(direction[0] * distance, 0, direction[1] * distance), maxSlope, radius);
                if (plan.isPresent()) return plan;
            }
        }
        return Optional.empty();
    }

    public Optional<Plan> at(ServerLevel level, SettlementSavedData data, SettlementTemplate template,
                             BlockPos center, int maxSlope, int configuredRadius) {
        int minX = center.getX() - template.marker().getX();
        int minZ = center.getZ() - template.marker().getZ();
        int maxX = minX + template.size().getX() - 1;
        int maxZ = minZ + template.size().getZ() - 1;
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(minX, 0, minZ))
                || !level.getWorldBorder().isWithinBounds(new BlockPos(maxX, 0, maxZ))) return Optional.empty();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) return Optional.empty();
            }
        }
        int radius = Math.max(configuredRadius, template.minimumTerritoryRadius());
        String dimension = level.dimension().location().toString();
        // Height is irrelevant to overlap, so reject occupied territories before scanning terrain.
        if (data.overlaps(new Territory(dimension, center.getX(), 0, center.getZ(), radius))) return Optional.empty();
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        int[][] surfaces = new int[template.size().getX()][template.size().getZ()];
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                surfaces[x - minX][z - minZ] = surface;
                BlockPos ground = new BlockPos(x, surface - 1, z);
                BlockState state = level.getBlockState(ground);
                if (!state.is(GROUND) || !state.getFluidState().isEmpty() || state.hasBlockEntity()
                        || !state.isFaceSturdy(level, ground, Direction.UP)) return Optional.empty();
                minSurface = Math.min(minSurface, surface);
                maxSurface = Math.max(maxSurface, surface);
                if (maxSurface - minSurface > maxSlope) return Optional.empty();
            }
        }
        BlockPos origin = new BlockPos(minX, maxSurface, minZ);
        BlockPos low = new BlockPos(minX, minSurface, minZ);
        BlockPos high = origin.offset(template.size().getX() - 1, template.size().getY() - 1, template.size().getZ() - 1);
        if (level.isOutsideBuildHeight(low.below()) || level.isOutsideBuildHeight(high)) return Optional.empty();
        List<BlockPos> supports = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = surfaces[x - minX][z - minZ]; y <= high.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().isEmpty() || state.hasBlockEntity()
                            || !(state.isAir() || state.is(CLEARABLE))) return Optional.empty();
                    if (y < origin.getY()) supports.add(pos);
                }
            }
        }
        AABB occupiedArea = new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1);
        if (!level.getEntities((Entity) null, occupiedArea, entity -> !entity.isSpectator()).isEmpty()) {
            return Optional.empty();
        }
        BlockPos marker = origin.offset(template.marker());
        Territory territory = new Territory(dimension, marker.getX(), marker.getY(), marker.getZ(), radius);
        return Optional.of(new Plan(origin, low, high, territory, List.copyOf(supports)));
    }

    public record Plan(BlockPos origin, BlockPos low, BlockPos high, Territory territory, List<BlockPos> supports) {}
}

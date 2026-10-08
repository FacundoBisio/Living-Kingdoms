package dev.livingkingdoms.structure;

import dev.livingkingdoms.LivingKingdoms;
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

import java.util.HashMap;
import java.util.Map;

import static dev.livingkingdoms.structure.GenerationDiagnostics.Rejection.*;

/** Read-only, per-attempt heightmap cache. Every read first checks chunk availability. */
final class SettlementTerrain {
    private static final TagKey<Block> GROUND = tag("settlement_ground");
    private static final TagKey<Block> CLEARABLE = tag("settlement_clearable");
    private final ServerLevel level;
    private final Map<Long, BlockPos> ground = new HashMap<>();

    SettlementTerrain(ServerLevel level) { this.level = level; }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, name));
    }

    void available(PlotBounds bounds) {
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(bounds.minX(), 0, bounds.minZ()))
                || !level.getWorldBorder().isWithinBounds(new BlockPos(bounds.maxX(), 0, bounds.maxZ()))) throw new Rejected(WORLD_BORDER);
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
            for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) throw new Rejected(UNLOADED_CHUNKS);
            }
        }
    }

    BlockPos ground(int x, int z, int foundationDepth) {
        available(new PlotBounds(x, z, x, z));
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        BlockPos found = ground.get(key);
        if (found == null) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            // Grass, snow layers and low vegetation must not become building foundations.
            for (int cleared = 0; cleared < 16 && y >= level.getMinBuildHeight()
                    && clearable(level.getBlockState(new BlockPos(x, y, z))); cleared++) y--;
            found = new BlockPos(x, y, z);
            ground.put(key, found);
        }
        for (int depth = 0; depth < foundationDepth; depth++) {
            BlockPos pos = found.below(depth);
            if (level.isOutsideBuildHeight(pos)) throw new Rejected(UNSUPPORTED_GROUND);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) throw new Rejected(WATER);
            if (state.hasBlockEntity() || !state.is(GROUND)) throw new Rejected(OBSTACLE);
            if (!state.isFaceSturdy(level, pos, Direction.UP) && !state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))
                throw new Rejected(UNSUPPORTED_GROUND);
        }
        return found;
    }

    static boolean clearable(BlockState state) { return state.is(CLEARABLE); }

    void clearVolume(BlockPos low, BlockPos high, Map<BlockPos, BlockState> before) {
        if (level.isOutsideBuildHeight(low) || level.isOutsideBuildHeight(high)) throw new Rejected(UNSUPPORTED_GROUND);
        for (BlockPos mutable : BlockPos.betweenClosed(low, high)) {
            BlockPos pos = mutable.immutable();
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) throw new Rejected(WATER);
            if (state.hasBlockEntity() || !(state.isAir() || clearable(state))) throw new Rejected(OBSTACLE);
            before.putIfAbsent(pos, state);
        }
        if (!level.getEntities((Entity) null, new AABB(low.getX(), low.getY(), low.getZ(),
                high.getX() + 1, high.getY() + 1, high.getZ() + 1), entity -> !entity.isSpectator()).isEmpty()) throw new Rejected(ENTITIES);
    }

    static final class Rejected extends RuntimeException {
        final GenerationDiagnostics.Rejection reason;
        Rejected(GenerationDiagnostics.Rejection reason) { super(reason.name(), null, false, false); this.reason = reason; }
    }
}

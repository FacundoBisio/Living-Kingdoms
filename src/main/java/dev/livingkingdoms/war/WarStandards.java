package dev.livingkingdoms.war;

import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Four black war standards (two dark oak fence posts topped by a black banner facing outwards) on the
 * diagonals near the territory edge, so a hostile kingdom is recognisable from afar. Placed once per
 * kingdom, only on loaded chunks, only on solid dry ground with free air above; at most 12 block writes.
 */
public final class WarStandards {
    private static final int EDGE_INSET = 4;

    private WarStandards() {}

    /** Returns how many standards were placed. Unsuitable spots are skipped, never forced. */
    public static int place(ServerLevel level, Settlement kingdom) {
        var territory = kingdom.territory();
        int distance = Math.max(4, territory.radius() - EDGE_INSET);
        int placed = 0;
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI / 4 + i * Math.PI / 2;
            int x = territory.x() + (int) Math.round(Math.cos(angle) * distance);
            int z = territory.z() + (int) Math.round(Math.sin(angle) * distance);
            if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos base = new BlockPos(x, y, z);
            if (!suitable(level, base)) continue;
            // Banner rotation is in sixteenths of a turn, 0 = facing south (+Z); face away from the center.
            double outward = Math.atan2(z - territory.z(), x - territory.x());
            int rotation = Math.floorMod((int) Math.round((Math.toDegrees(outward) - 90) / 22.5), 16);
            level.setBlock(base, Blocks.DARK_OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(base.above(), Blocks.DARK_OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(base.above(2), Blocks.BLACK_BANNER.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rotation),
                    Block.UPDATE_ALL);
            placed++;
        }
        return placed;
    }

    private static boolean suitable(ServerLevel level, BlockPos base) {
        if (base.getY() <= level.getMinBuildHeight() || base.getY() + 3 >= level.getMaxBuildHeight()) return false;
        BlockPos ground = base.below();
        var groundState = level.getBlockState(ground);
        if (!groundState.getFluidState().isEmpty() || !groundState.isFaceSturdy(level, ground, Direction.UP)) return false;
        for (int dy = 0; dy <= 2; dy++) {
            var state = level.getBlockState(base.above(dy));
            if (!state.isAir() || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }
}

package dev.livingkingdoms.settlement;

import dev.livingkingdoms.block.KingdomBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal, two-block conversion infrastructure. Requires empty dry space and never clears vanilla construction. */
public record ConversionInfrastructure(BlockPos marker, BlockPos board) {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public static Optional<ConversionInfrastructure> plan(ServerLevel level, BlockPos center) {
        for (int distance = 0; distance <= 12; distance++) {
            for (int x = -distance; x <= distance; x++) for (int z = -distance; z <= distance; z++) {
                if (Math.max(Math.abs(x),Math.abs(z)) != distance) continue;
                BlockPos column = center.offset(x,0,z);
                if (!level.getChunkSource().hasChunk(column.getX()>>4,column.getZ()>>4)) continue;
                BlockPos marker = new BlockPos(column.getX(),level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        column.getX(),column.getZ()),column.getZ());
                if (Math.abs(marker.getY()-center.getY()) > 3) continue;
                var plan = new ConversionInfrastructure(marker, marker.east(2));
                if (plan.safe(level)) return Optional.of(plan);
            }
        }
        return Optional.empty();
    }

    private boolean safe(ServerLevel level) {
        for (BlockPos feet : List.of(marker,board,marker.north(3),board.south())) {
            if (feet.getY() <= level.getMinBuildHeight() || feet.getY()+3 >= level.getMaxBuildHeight()
                    || !level.getChunkSource().hasChunk(feet.getX()>>4,feet.getZ()>>4)
                    || !level.getWorldBorder().isWithinBounds(feet)) return false;
            var ground = level.getBlockState(feet.below());
            if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level,feet.below(),Direction.UP)) return false;
            for (int y = 0; y < 3; y++) if (!level.getBlockState(feet.above(y)).isAir()) return false;
            if (!level.getEntities((Entity)null,new AABB(feet).expandTowards(0,2,0),
                    entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()) return false;
        }
        return true;
    }

    public Placement apply(ServerLevel level) {
        if (!level.getServer().isSameThread() || !safe(level)) throw new IllegalStateException("Conversion site changed");
        Placement placement = new Placement(level);
        try {
            placement.write(marker,Blocks.LODESTONE.defaultBlockState());
            placement.write(board,KingdomBlocks.QUEST_BOARD.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,Direction.SOUTH));
            return placement;
        } catch (RuntimeException failure) { placement.close(); throw failure; }
    }

    public static final class Placement implements AutoCloseable {
        private final ServerLevel level;
        private final Map<BlockPos,BlockState> before = new LinkedHashMap<>();
        private boolean committed;
        private Placement(ServerLevel level) { this.level = level; }
        private void write(BlockPos pos, BlockState state) {
            before.put(pos,level.getBlockState(pos));
            if (!level.setBlock(pos,state,FLAGS) || !level.getBlockState(pos).equals(state))
                throw new IllegalStateException("Conversion infrastructure placement refused");
        }
        public void commit() { committed = true; }
        @Override public void close() {
            if (!committed) before.forEach((pos,state) -> { level.removeBlockEntity(pos); level.setBlock(pos,state,FLAGS); });
        }
    }
}

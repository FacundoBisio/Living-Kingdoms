package dev.livingkingdoms.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.Map;

/** Transaction remains reversible until its caller has recorded the successful placement. */
public final class SettlementPlacement implements AutoCloseable {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private final ServerLevel level;
    private final SettlementLayout plan;
    private boolean committed;

    private SettlementPlacement(ServerLevel level, SettlementLayout plan) { this.level = level; this.plan = plan; }

    public static SettlementPlacement apply(ServerLevel level, SettlementLayout plan) {
        if (!level.getServer().isSameThread() || !level.dimension().location().toString().equals(plan.territory().dimension()))
            throw new IllegalStateException("Placement requires the owning server thread and dimension");
        // Revalidate the entire immutable snapshot before the first write, even for delayed growth requests.
        for (var entry : plan.before().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || !level.getWorldBorder().isWithinBounds(pos)
                    || !plan.territory().contains(plan.territory().dimension(), pos.getX(), pos.getZ())
                    || !level.getBlockState(pos).equals(entry.getValue()) || level.getBlockEntity(pos) != null)
                throw new IllegalStateException("Planned terrain changed or became unavailable at " + pos);
            if (!level.getEntities((Entity) null, new AABB(pos), entity -> !entity.isSpectator()).isEmpty())
                throw new IllegalStateException("An entity entered planned placement at " + pos);
        }
        SettlementPlacement transaction = new SettlementPlacement(level, plan);
        try {
            for (var building : plan.buildings()) {
                for (BlockPos support : building.supports()) transaction.write(support, Blocks.COBBLESTONE.defaultBlockState());
                // Clear only the validated module volume, including sparse future native exports.
                for (BlockPos pos : BlockPos.betweenClosed(building.origin(), building.high()))
                    transaction.write(pos.immutable(), Blocks.AIR.defaultBlockState());
                var settings = SettlementTemplate.settings().setRotation(building.rotation())
                        .setBoundingBox(BoundingBox.fromCorners(building.origin(), building.high()));
                BlockPos nativeOrigin = building.module().nativeOrigin(building.origin(), building.rotation());
                if (!building.module().template().placeInWorld(level, nativeOrigin, nativeOrigin, settings, level.random, FLAGS))
                    throw new IllegalStateException("Native module placement refused");
                for (var info : building.module().blocks()) {
                    if (!level.getBlockState(building.position(info.pos())).equals(info.state().rotate(building.rotation())))
                        throw new IllegalStateException("Incomplete native module: " + building.module().id());
                }
            }
            for (var entry : plan.pathBlocks().entrySet()) transaction.write(entry.getKey(), entry.getValue());
            return transaction;
        } catch (RuntimeException failure) {
            transaction.close();
            throw failure;
        }
    }

    private void write(BlockPos pos, BlockState state) {
        if (!plan.before().containsKey(pos)) throw new IllegalStateException("Unplanned write: " + pos);
        if (!level.getBlockState(pos).equals(state) && !level.setBlock(pos, state, FLAGS))
            throw new IllegalStateException("Block placement refused at " + pos);
        if (!level.getBlockState(pos).equals(state)) throw new IllegalStateException("Incomplete write at " + pos);
    }

    public void commit() { committed = true; }

    @Override public void close() {
        if (committed) return;
        // Restore states in two passes so module block entities are removed before any original state is rebuilt.
        for (BlockPos pos : plan.before().keySet()) {
            if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
        }
        for (Map.Entry<BlockPos, BlockState> entry : plan.before().entrySet()) {
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                if (!level.setBlock(entry.getKey(), entry.getValue(), FLAGS)
                        || !level.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("Rollback refused at " + entry.getKey());
            }
        }
        committed = true;
    }
}

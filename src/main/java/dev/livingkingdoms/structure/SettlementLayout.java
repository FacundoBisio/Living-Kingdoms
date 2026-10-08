package dev.livingkingdoms.structure;

import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/** Complete immutable plan. Original states are preconditions as well as the rollback snapshot. */
public record SettlementLayout(Territory territory, ArchitectureStyle style, List<Building> buildings,
                               Map<BlockPos, BlockState> pathBlocks, Map<BlockPos, BlockState> before) {
    public SettlementLayout {
        buildings = List.copyOf(buildings);
        pathBlocks = Map.copyOf(pathBlocks);
        before = Map.copyOf(before);
    }

    public record Building(BuildingTemplate module, BlockPos origin, Rotation rotation, PlotBounds bounds,
                           BlockPos entrance, List<BlockPos> supports) {
        public Building { supports = List.copyOf(supports); }
        public BlockPos high() { return origin.offset(module.rotatedSize(rotation)).offset(-1, -1, -1); }
        public BlockPos position(BlockPos local) { return module.worldPosition(local, origin, rotation); }
    }
}

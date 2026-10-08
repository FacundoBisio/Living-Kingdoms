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
        public net.minecraft.world.level.levelgen.structure.BoundingBox volume() {
            BlockPos bottom = supports.stream().min(java.util.Comparator.comparingInt(BlockPos::getY)).orElse(origin);
            return net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                    new BlockPos(origin.getX(), Math.min(bottom.getY(), origin.getY()), origin.getZ()), high());
        }
    }

    /** Check geometry independently of terrain snapshots, before ANY block mutation.
     * Includes exported air (clearance), overhangs, decorations, supports and path headroom. */
    public void validateGeometry() {
        for (int i = 0; i < buildings.size(); i++) {
            var building = buildings.get(i);
            var nativeBounds = building.module().worldBounds(building.origin(), building.rotation());
            var high = building.high();
            if (nativeBounds.minX() != building.origin().getX() || nativeBounds.minY() != building.origin().getY()
                    || nativeBounds.minZ() != building.origin().getZ() || nativeBounds.maxX() != high.getX()
                    || nativeBounds.maxY() != high.getY() || nativeBounds.maxZ() != high.getZ()
                    || building.bounds().minX() != nativeBounds.minX() || building.bounds().maxX() != nativeBounds.maxX()
                    || building.bounds().minZ() != nativeBounds.minZ() || building.bounds().maxZ() != nativeBounds.maxZ()
                    || !building.entrance().equals(building.position(building.module().entrance()))
                    || !building.bounds().inside(territory))
                throw new IllegalStateException("Template transform/footprint mismatch: " + building.module().id());
            var occupied = new java.util.HashSet<BlockPos>();
            for (var block : building.module().blocks()) {
                BlockPos pos = building.position(block.pos());
                if (!nativeBounds.isInside(pos) || !occupied.add(pos) || !before.containsKey(pos))
                    throw new IllegalStateException("Invalid transformed module block: " + pos);
            }
            for (BlockPos support : building.supports()) {
                if (!building.bounds().contains(support.getX(), support.getZ()) || support.getY() >= building.origin().getY()
                        || !before.containsKey(support)) throw new IllegalStateException("Invalid foundation support: " + support);
            }
            for (int j = 0; j < i; j++) {
                // Volume comparison explicitly covers roofs and foundation heights.
                // Horizontal reservations remain stronger: stacking buildings is unsupported.
                if (building.volume().intersects(buildings.get(j).volume())
                        || building.bounds().conflicts(buildings.get(j).bounds(), 0))
                    throw new IllegalStateException("Overlapping buildings before placement");
            }
            for (BlockPos path : pathBlocks.keySet()) {
                if (building.volume().isInside(path)) throw new IllegalStateException("Path intersects building clearance: " + path);
            }
        }
        for (BlockPos path : pathBlocks.keySet()) {
            if (!before.containsKey(path) || !territory.contains(territory.dimension(), path.getX(), path.getZ()))
                throw new IllegalStateException("Unplanned path write: " + path);
        }
    }
}

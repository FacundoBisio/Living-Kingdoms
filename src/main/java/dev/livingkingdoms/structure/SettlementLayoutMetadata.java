package dev.livingkingdoms.structure;

import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

/** Optional metadata for new settlements; legacy settlements do not require a physical layout record. */
public record SettlementLayoutMetadata(ArchitectureStyle style, List<Building> buildings, List<BlockPos> ports,
                                       List<BlockPos> paths) {
    public SettlementLayoutMetadata {
        buildings = List.copyOf(buildings);
        ports = List.copyOf(ports);
        paths = List.copyOf(paths);
        java.util.Objects.requireNonNull(style);
        if (buildings.isEmpty() || buildings.size() > 256 || ports.isEmpty() || ports.size() > 16 || paths.size() > 16384)
            throw new IllegalArgumentException("Invalid layout metadata size");
    }

    public static SettlementLayoutMetadata from(SettlementLayout plan) {
        var core = plan.buildings().stream().filter(building -> anchor(building.module().kind())).findFirst().orElseThrow();
        if (core.module().kind() == BuildingKind.FOUNDING_CAMP)
            return new SettlementLayoutMetadata(plan.style(), descriptions(plan), List.of(core.position(new BlockPos(0, 0, 4)),
                    core.position(new BlockPos(8, 0, 4)), core.position(new BlockPos(4, 0, 8))), pathFloors(plan));
        return new SettlementLayoutMetadata(plan.style(), descriptions(plan), List.of(core.position(new BlockPos(0, 0, 9)),
                core.position(new BlockPos(12, 0, 9)), core.position(new BlockPos(6, 0, 12))), pathFloors(plan));
    }

    public SettlementLayoutMetadata append(SettlementLayout addition) {
        List<Building> all = new ArrayList<>(buildings);
        all.addAll(descriptions(addition));
        List<BlockPos> routes = new ArrayList<>(paths);
        routes.addAll(pathFloors(addition));
        return new SettlementLayoutMetadata(style, all, ports, routes.stream().distinct().toList());
    }

    public void validate(Territory territory) {
        List<Building> cores = buildings.stream().filter(building -> anchor(building.kind)).toList();
        boolean camp = cores.size() == 1 && cores.getFirst().kind == BuildingKind.FOUNDING_CAMP;
        if (cores.size() != 1 || cores.getFirst().rotation != Rotation.NONE
                || !cores.getFirst().origin.offset(camp ? 4 : 6, 1, camp ? 4 : 9).equals(new BlockPos(territory.x(), territory.y(), territory.z()))
                || cores.getFirst().bounds.maxX() - cores.getFirst().bounds.minX() != (camp ? 8 : 12)
                || cores.getFirst().bounds.maxZ() - cores.getFirst().bounds.minZ() != (camp ? 8 : 12))
            throw new IllegalArgumentException("Layout core does not match founding marker");
        for (int i = 0; i < buildings.size(); i++) {
            Building building = buildings.get(i);
            if (!building.bounds.inside(territory)
                    || !territory.contains(territory.dimension(), building.entrance.getX(), building.entrance.getZ()))
                throw new IllegalArgumentException("Layout outside territory");
            for (int j = 0; j < i; j++) {
                if (building.bounds.conflicts(buildings.get(j).bounds, 0)) throw new IllegalArgumentException("Overlapping saved buildings");
            }
        }
        if (ports.stream().anyMatch(pos -> !territory.contains(territory.dimension(), pos.getX(), pos.getZ()))
                || paths.stream().anyMatch(pos -> !territory.contains(territory.dimension(), pos.getX(), pos.getZ())))
            throw new IllegalArgumentException("Layout connections outside territory");
    }

    private static List<Building> descriptions(SettlementLayout plan) {
        return plan.buildings().stream().map(building -> new Building(building.module().kind(), building.module().id(),
                building.origin(), building.rotation(), building.bounds(), building.entrance())).toList();
    }

    public static boolean anchor(BuildingKind kind) { return kind == BuildingKind.CORE || kind == BuildingKind.FOUNDING_CAMP; }

    private static List<BlockPos> pathFloors(SettlementLayout plan) {
        return plan.pathBlocks().entrySet().stream().filter(entry -> SettlementPathPlanner.isPathFloor(entry.getValue())).map(java.util.Map.Entry::getKey).toList();
    }

    public record Building(BuildingKind kind, ResourceLocation template, BlockPos origin, Rotation rotation,
                           PlotBounds bounds, BlockPos entrance) {
        public Building {
            java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(template);
            java.util.Objects.requireNonNull(origin); java.util.Objects.requireNonNull(rotation);
            java.util.Objects.requireNonNull(bounds); java.util.Objects.requireNonNull(entrance);
            if (bounds.maxX() - (long) bounds.minX() > 14 || bounds.maxZ() - (long) bounds.minZ() > 14
                    || origin.getX() != bounds.minX() || origin.getZ() != bounds.minZ()) throw new IllegalArgumentException("Invalid saved footprint");
        }
    }
}

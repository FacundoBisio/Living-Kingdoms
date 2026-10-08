package dev.livingkingdoms.structure;

import dev.livingkingdoms.config.SettlementGenerationConfig;
import dev.livingkingdoms.config.SettlementGenerationConfig.Tolerance;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.livingkingdoms.structure.GenerationDiagnostics.Rejection.*;

/** PLAN -> VALIDATE -> APPLY. This class never places blocks, NPCs, or saved records. */
public final class SettlementLayoutPlanner {
    private static final int[][] DIRECTIONS = {{1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {-1,1}, {-1,-1}, {1,-1}};
    private static final List<BuildingKind> INITIAL = List.of(BuildingKind.HOUSE, BuildingKind.HOUSE_VARIANT,
            BuildingKind.BLACKSMITH, BuildingKind.BARRACKS);
    private final SettlementPathPlanner paths = new SettlementPathPlanner();

    public Optional<SettlementLayout> findNear(ServerLevel level, SettlementSavedData data, BuildingCatalog catalog,
            BlockPos player, int searchRange, int radius, GenerationDiagnostics diagnostics) {
        for (int distance = 32; distance <= searchRange; distance += 16) {
            for (int[] direction : DIRECTIONS) {
                if (Math.hypot(direction[0] * distance, direction[1] * distance) > searchRange) continue;
                var found = at(level, data, catalog, player.offset(direction[0] * distance, 0, direction[1] * distance),
                        radius, false, diagnostics);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    /** Small, bounded local search. Caller obstruction can invalidate one center, not the whole area. */
    public Optional<SettlementLayout> findHere(ServerLevel level, SettlementSavedData data, BuildingCatalog catalog,
            BlockPos player, int radius, GenerationDiagnostics diagnostics) {
        BlockPos first = player.north(4);
        var exact = at(level, data, catalog, first, radius, true, diagnostics);
        if (exact.isPresent()) return exact;
        for (int distance : new int[]{8, 16, 24}) {
            for (int[] direction : DIRECTIONS) {
                var found = at(level, data, catalog, first.offset(direction[0] * distance, 0, direction[1] * distance),
                        radius, true, diagnostics);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    public Optional<SettlementLayout> at(ServerLevel level, SettlementSavedData data, BuildingCatalog catalog,
            BlockPos center, int radius, boolean relaxed, GenerationDiagnostics diagnostics) {
        diagnostics.centerChecked();
        SettlementTerrain terrain = new SettlementTerrain(level);
        boolean corePlanned = false;
        Territory territory = new Territory(level.dimension().location().toString(), center.getX(), center.getY(), center.getZ(), radius);
        try {
            if (!level.getWorldBorder().isWithinBounds(center.offset(-radius, 0, -radius))
                    || !level.getWorldBorder().isWithinBounds(center.offset(radius, 0, radius))) throw new SettlementTerrain.Rejected(WORLD_BORDER);
            if (data.overlaps(territory)) throw new SettlementTerrain.Rejected(SETTLEMENT_OVERLAP);
            Map<BlockPos, BlockState> before = new LinkedHashMap<>();
            var core = plot(level, terrain, territory, catalog.get(BuildingKind.CORE),
                    center.offset(-6, 0, -9), Rotation.NONE, SettlementGenerationConfig.tolerance(BuildingKind.CORE, relaxed), before);
            corePlanned = true;
            BlockPos marker = core.position(new BlockPos(6, 1, 9));
            territory = new Territory(territory.dimension(), marker.getX(), marker.getY(), marker.getZ(), radius);
            List<SettlementLayout.Building> buildings = new ArrayList<>();
            buildings.add(core);
            Map<BlockPos, BlockState> pathBlocks = new LinkedHashMap<>();
            List<BlockPos> ports = List.of(core.position(new BlockPos(0, 0, 9)), core.position(new BlockPos(12, 0, 9)),
                    core.position(new BlockPos(6, 0, 12)));
            List<BuildingKind> initial = new ArrayList<>(INITIAL);
            if (Math.floorMod(center.getX() * 31L + center.getZ(), 3) == 0) initial.set(1, BuildingKind.HOUSE_THIRD);
            for (BuildingKind kind : initial) {
                var addition = searchAddition(level, terrain, territory, catalog.get(kind),
                        buildings.stream().map(SettlementLayout.Building::bounds).toList(), ports, pathBlocks,
                        relaxed, diagnostics);
                diagnostics.validPlots(buildings.size() - 1);
                if (addition.isEmpty()) throw new SettlementTerrain.Rejected(INSUFFICIENT_PLOTS);
                SettlementLayout piece = addition.orElseThrow();
                buildings.addAll(piece.buildings());
                piece.before().forEach(before::putIfAbsent);
                pathBlocks.putAll(piece.pathBlocks());
            }
            diagnostics.validPlots(initial.size());
            // Skyline accent is optional; the four functional plots remain the minimum village.
            var tower = searchAddition(level, terrain, territory, catalog.get(BuildingKind.WATCHTOWER),
                    buildings.stream().map(SettlementLayout.Building::bounds).toList(), ports, pathBlocks, relaxed, diagnostics);
            if (tower.isPresent()) {
                buildings.addAll(tower.get().buildings());
                tower.get().before().forEach(before::putIfAbsent);
                pathBlocks.putAll(tower.get().pathBlocks());
            }
            return Optional.of(new SettlementLayout(territory, ArchitectureStyle.at(level, marker), buildings, pathBlocks, before));
        } catch (SettlementTerrain.Rejected rejected) {
            diagnostics.rejectCenter(rejected.reason);
            if (!corePlanned && rejected.reason != SETTLEMENT_OVERLAP && rejected.reason != WORLD_BORDER
                    && rejected.reason != UNLOADED_CHUNKS) diagnostics.rejectCenter(INSUFFICIENT_CORE_AREA);
            return Optional.empty();
        }
    }

    public Optional<SettlementLayout> findCampHere(ServerLevel level, SettlementSavedData data, BuildingCatalog catalog,
            BlockPos player, int radius, GenerationDiagnostics diagnostics) {
        for (int distance : new int[]{0, 8, 16, 24}) {
            for (int[] direction : DIRECTIONS) {
                if (distance == 0 && direction != DIRECTIONS[0]) continue;
                BlockPos center = player.north(6).offset(direction[0] * distance, 0, direction[1] * distance);
                diagnostics.centerChecked();
                Territory territory = new Territory(level.dimension().location().toString(), center.getX(), center.getY(), center.getZ(), radius);
                if (data.overlaps(territory)) { diagnostics.rejectCenter(SETTLEMENT_OVERLAP); continue; }
                if (!level.getWorldBorder().isWithinBounds(center.offset(-radius, 0, -radius))
                        || !level.getWorldBorder().isWithinBounds(center.offset(radius, 0, radius))) {
                    diagnostics.rejectCenter(WORLD_BORDER); continue;
                }
                var priorFailures = diagnostics.summary().plotFailures();
                var plot = planPlot(level, territory, catalog.get(BuildingKind.FOUNDING_CAMP), center.offset(-4, 0, -4),
                        Rotation.NONE, SettlementGenerationConfig.tolerance(BuildingKind.FOUNDING_CAMP, true), diagnostics);
                if (plot.isPresent()) {
                    var plan = plot.orElseThrow();
                    var marker = plan.buildings().getFirst().position(new BlockPos(4, 1, 4));
                    return Optional.of(new SettlementLayout(new Territory(territory.dimension(), marker.getX(), marker.getY(), marker.getZ(), radius),
                            plan.style(), plan.buildings(), plan.pathBlocks(), plan.before()));
                }
                // planPlot reports its precise reason as a plot rejection. The founding
                // interaction also needs center diagnostics, just like the full-core path.
                var failures = diagnostics.summary().plotFailures();
                var reason = java.util.Arrays.stream(GenerationDiagnostics.Rejection.values())
                        .filter(value -> failures.getOrDefault(value,0) > priorFailures.getOrDefault(value,0)).findFirst().orElse(INSUFFICIENT_CORE_AREA);
                diagnostics.rejectCenter(reason);
            }
        }
        return Optional.empty();
    }

    /** Reusable for future growth. Caller supplies persisted occupied footprints and reachable plaza ports.
     * The returned single-building plan uses the same atomic applicator as initial generation. */
    public Optional<SettlementLayout> planAddition(ServerLevel level, Territory territory, BuildingCatalog catalog,
            BuildingKind kind, List<PlotBounds> occupied, List<BlockPos> plazaPorts,
            Map<BlockPos, BlockState> existingPaths, GenerationDiagnostics diagnostics) {
        if (SettlementLayoutMetadata.anchor(kind) || !territory.dimension().equals(level.dimension().location().toString())
                || plazaPorts.isEmpty() || plazaPorts.stream().anyMatch(port -> !territory.contains(territory.dimension(), port.getX(), port.getZ())))
            throw new IllegalArgumentException("Invalid expansion context");
        return searchAddition(level, new SettlementTerrain(level), territory, catalog.get(kind), List.copyOf(occupied),
                List.copyOf(plazaPorts), existingPaths, false, diagnostics);
    }

    private Optional<SettlementLayout> searchAddition(ServerLevel level, SettlementTerrain terrain, Territory territory,
            BuildingTemplate module, List<PlotBounds> occupied, List<BlockPos> ports, Map<BlockPos, BlockState> existingPaths,
            boolean relaxed, GenerationDiagnostics diagnostics) {
        int searchRadius = Math.min(SettlementGenerationConfig.PLOT_SEARCH_RADIUS.get(), territory.radius());
        int spacing = SettlementGenerationConfig.SPACING.get();
        // Golden-angle sampling creates deterministic, non-grid layouts with modest position variation.
        double phase = Math.floorMod(territory.x() * 31L + territory.z() * 17L, 360) * Math.PI / 180 + module.kind().ordinal() * 0.7;
        for (int attempt = 0; attempt < SettlementGenerationConfig.PLOT_ATTEMPTS.get(); attempt++) {
            diagnostics.plotChecked();
            double angle = phase + attempt * 2.399963229728653;
            int distance = Math.min(searchRadius, 13 + attempt / 8 * 3);
            int cx = territory.x() + (int) Math.round(Math.cos(angle) * distance);
            int cz = territory.z() + (int) Math.round(Math.sin(angle) * distance);
            Rotation rotation = facingCore(cx - territory.x(), cz - territory.z());
            Vec3i size = module.rotatedSize(rotation);
            BlockPos minimum = new BlockPos(cx - size.getX() / 2, territory.y(), cz - size.getZ() / 2);
            PlotBounds bounds = bounds(minimum, size);
            try {
                if (occupied.stream().anyMatch(other -> bounds.conflicts(other, spacing))
                        || existingPaths.keySet().stream().anyMatch(pos -> bounds.contains(pos.getX(), pos.getZ())))
                    throw new SettlementTerrain.Rejected(OBSTACLE);
                Map<BlockPos, BlockState> before = new LinkedHashMap<>();
                var building = plot(level, terrain, territory, module, minimum, rotation,
                        SettlementGenerationConfig.tolerance(module.kind(), relaxed), before);
                List<PlotBounds> allOccupied = new ArrayList<>(occupied);
                allOccupied.add(bounds);
                List<BlockPos> orderedPorts = ports.stream().sorted(Comparator.comparingDouble(port ->
                        port.distSqr(building.entrance()))).toList();
                Optional<Map<BlockPos, BlockState>> route = Optional.empty();
                for (BlockPos port : orderedPorts) {
                    Map<BlockPos, BlockState> pathBefore = new LinkedHashMap<>();
                    route = paths.connect(level, terrain, territory, building.entrance(), port, allOccupied,
                            existingPaths, pathBefore, SettlementGenerationConfig.PATH_NODES.get());
                    if (route.isPresent()) { pathBefore.forEach(before::putIfAbsent); break; }
                }
                if (route.isEmpty()) throw new SettlementTerrain.Rejected(PATH);
                return Optional.of(new SettlementLayout(territory, ArchitectureStyle.at(level, minimum),
                        List.of(building), route.orElseThrow(), before));
            } catch (SettlementTerrain.Rejected rejected) { diagnostics.rejectPlot(rejected.reason); }
        }
        return Optional.empty();
    }

    /** Public plot validation is useful for authoring tests and explicit future building requests. */
    public Optional<SettlementLayout> planPlot(ServerLevel level, Territory territory, BuildingTemplate module,
            BlockPos minimumCorner, Rotation rotation, Tolerance tolerance, GenerationDiagnostics diagnostics) {
        diagnostics.plotChecked();
        try {
            if (!territory.dimension().equals(level.dimension().location().toString())) throw new SettlementTerrain.Rejected(TERRITORY);
            Map<BlockPos, BlockState> before = new LinkedHashMap<>();
            var building = plot(level, new SettlementTerrain(level), territory, module, minimumCorner, rotation, tolerance, before);
            return Optional.of(new SettlementLayout(territory, ArchitectureStyle.at(level, minimumCorner), List.of(building), Map.of(), before));
        } catch (SettlementTerrain.Rejected rejected) { diagnostics.rejectPlot(rejected.reason); return Optional.empty(); }
    }

    private SettlementLayout.Building plot(ServerLevel level, SettlementTerrain terrain, Territory territory,
            BuildingTemplate module, BlockPos minimum, Rotation rotation, Tolerance tolerance, Map<BlockPos, BlockState> before) {
        Vec3i size = module.rotatedSize(rotation);
        PlotBounds bounds = bounds(minimum, size);
        terrain.available(bounds);
        if (!bounds.inside(territory)) throw new SettlementTerrain.Rejected(TERRITORY);
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        List<BlockPos> ground = new ArrayList<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                BlockPos natural = terrain.ground(x, z, tolerance.foundationDepth());
                for (int depth = 0; depth < tolerance.foundationDepth(); depth++) {
                    BlockPos anchor = natural.below(depth);
                    before.putIfAbsent(anchor, level.getBlockState(anchor));
                }
                ground.add(natural);
                low = Math.min(low, natural.getY() + 1);
                high = Math.max(high, natural.getY() + 1);
                if (high - low > tolerance.maxVariance() || high - low > tolerance.maxSupportDepth())
                    throw new SettlementTerrain.Rejected(EXCESSIVE_SLOPE);
            }
        }
        BlockPos origin = new BlockPos(minimum.getX(), high, minimum.getZ());
        List<BlockPos> supports = new ArrayList<>();
        for (BlockPos natural : ground) {
            terrain.clearVolume(natural.above(), new BlockPos(natural.getX(), high + size.getY() - 1, natural.getZ()), before);
            for (int y = natural.getY() + 1; y < high; y++) supports.add(new BlockPos(natural.getX(), y, natural.getZ()));
        }
        return new SettlementLayout.Building(module, origin, rotation, bounds,
                module.worldPosition(module.entrance(), origin, rotation), supports);
    }

    private static PlotBounds bounds(BlockPos minimum, Vec3i size) {
        return new PlotBounds(minimum.getX(), minimum.getZ(), minimum.getX() + size.getX() - 1, minimum.getZ() + size.getZ() - 1);
    }

    private static Rotation facingCore(int dx, int dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
        return dz > 0 ? Rotation.CLOCKWISE_180 : Rotation.NONE;
    }
}

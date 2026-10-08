package dev.livingkingdoms.structure;

import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded local surface routing, with one-block steps and a six-block detour margin. */
final class SettlementPathPlanner {
    private static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    Optional<Map<BlockPos, BlockState>> connect(ServerLevel level, SettlementTerrain terrain, Territory territory,
            BlockPos start, BlockPos goal, List<PlotBounds> occupied, Map<BlockPos, BlockState> existingPaths,
            Map<BlockPos, BlockState> before, int budget) {
        PlotBounds corridor = new PlotBounds(Math.min(start.getX(), goal.getX()) - 6, Math.min(start.getZ(), goal.getZ()) - 6,
                Math.max(start.getX(), goal.getX()) + 6, Math.max(start.getZ(), goal.getZ()) + 6);
        Map<Cell, BlockPos> positions = new HashMap<>();
        Map<Cell, Integer> scores = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Set<Cell> closed = new HashSet<>();
        Cell source = Cell.of(start), target = Cell.of(goal);
        try {
            terrain.available(new PlotBounds(start.getX(), start.getZ(), start.getX(), start.getZ()));
            terrain.available(new PlotBounds(goal.getX(), goal.getZ(), goal.getX(), goal.getZ()));
        }
        catch (SettlementTerrain.Rejected unavailable) { return Optional.empty(); }
        positions.put(source, start);
        positions.put(target, goal);
        var queue = new PriorityQueue<Node>(Comparator.comparingInt(Node::priority).thenComparingInt(node -> node.cell.x)
                .thenComparingInt(node -> node.cell.z));
        scores.put(source, 0);
        queue.add(new Node(source, source.distance(target)));
        int checked = 0;
        while (!queue.isEmpty() && checked++ < budget) {
            Cell cell = queue.remove().cell;
            if (!closed.add(cell)) continue;
            if (cell.equals(target)) {
                List<BlockPos> route = new ArrayList<>();
                for (Cell step = target; step != null; step = previous.get(step)) route.add(positions.get(step));
                java.util.Collections.reverse(route);
                try { return Optional.of(materialize(level, terrain, route, existingPaths, before)); }
                catch (SettlementTerrain.Rejected rejected) { return Optional.empty(); }
            }
            for (Direction direction : DIRECTIONS) {
                Cell next = new Cell(cell.x + direction.getStepX(), cell.z + direction.getStepZ());
                if (closed.contains(next) || !corridor.contains(next.x, next.z)
                        || !territory.contains(territory.dimension(), next.x, next.z)) continue;
                if (!next.equals(target) && occupied.stream().anyMatch(bounds -> bounds.contains(next.x, next.z))) continue;
                BlockPos pos;
                try {
                    pos = positions.get(next);
                    if (pos == null) {
                        pos = existingFloor(existingPaths, next.x, next.z);
                        if (pos == null) {
                            pos = terrain.ground(next.x, next.z, 1);
                            terrain.clearVolume(pos.above(), pos.above(3), new HashMap<>());
                        } else {
                            terrain.available(new PlotBounds(next.x, next.z, next.x, next.z));
                            if (isPathFloor(level.getBlockState(pos)))
                                validateExistingPath(level, terrain, pos, new HashMap<>());
                            else terrain.clearVolume(terrain.ground(next.x, next.z, 1).above(), pos.above(3), new HashMap<>());
                        }
                        positions.put(next, pos);
                    }
                    if (Math.abs(pos.getY() - positions.get(cell).getY()) > 1) continue;
                    int score = scores.get(cell) + 1 + Math.abs(pos.getY() - positions.get(cell).getY());
                    if (score < scores.getOrDefault(next, Integer.MAX_VALUE)) {
                        scores.put(next, score);
                        previous.put(next, cell);
                        queue.add(new Node(next, score + next.distance(target)));
                    }
                } catch (SettlementTerrain.Rejected ignored) { /* Try another surface column. */ }
            }
        }
        return Optional.empty();
    }

    private Map<BlockPos, BlockState> materialize(ServerLevel level, SettlementTerrain terrain, List<BlockPos> route,
                                                 Map<BlockPos, BlockState> existingPaths, Map<BlockPos, BlockState> before) {
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        // Goal is an existing/planned plaza floor and must remain part of its native template.
        for (int i = 0; i < route.size() - 1; i++) {
            BlockPos floor = route.get(i);
            if (existingPaths.containsKey(floor) && isPathFloor(level.getBlockState(floor))) {
                validateExistingPath(level, terrain, floor, before);
                continue;
            }
            BlockPos natural = terrain.ground(floor.getX(), floor.getZ(), 1);
            if (floor.getY() < natural.getY() || floor.getY() - natural.getY() > 4)
                throw new SettlementTerrain.Rejected(GenerationDiagnostics.Rejection.PATH);
            terrain.clearVolume(natural.above(), floor.above(3), before);
            before.putIfAbsent(natural, level.getBlockState(natural));
            for (int y = natural.getY() + 1; y < floor.getY(); y++)
                writes.put(new BlockPos(floor.getX(), y, floor.getZ()), Blocks.COBBLESTONE.defaultBlockState());
            int variation = Math.floorMod(floor.getX()*31L + floor.getZ()*17L, 10);
            BlockState paving = variation == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : variation <= 2
                    ? Blocks.GRAVEL.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState();
            writes.put(floor, paving);
            for (int y = 1; y <= 3; y++) writes.put(floor.above(y), Blocks.AIR.defaultBlockState());
            BlockPos higher = null;
            if (route.get(i + 1).getY() > floor.getY()) higher = route.get(i + 1);
            else if (i > 0 && route.get(i - 1).getY() > floor.getY()) higher = route.get(i - 1);
            if (higher != null) {
                Direction facing = Direction.fromDelta(higher.getX() - floor.getX(), 0, higher.getZ() - floor.getZ());
                writes.put(floor.above(), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing));
            }
        }
        return writes;
    }

    static boolean isPathFloor(BlockState state) {
        return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.COARSE_DIRT);
    }

    private static BlockPos existingFloor(Map<BlockPos, BlockState> existing, int x, int z) {
        for (var entry : existing.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getX() == x && pos.getZ() == z && isPathFloor(entry.getValue()))
                return pos;
        }
        return null;
    }

    private static void validateExistingPath(ServerLevel level, SettlementTerrain terrain, BlockPos floor,
                                             Map<BlockPos, BlockState> before) {
        terrain.available(new PlotBounds(floor.getX(), floor.getZ(), floor.getX(), floor.getZ()));
        if (level.isOutsideBuildHeight(floor.above(3))) throw new SettlementTerrain.Rejected(GenerationDiagnostics.Rejection.PATH);
        for (int y = 0; y <= 3; y++) {
            BlockPos pos = floor.above(y);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || state.hasBlockEntity()
                    || y > 0 && !(state.isAir() || SettlementTerrain.clearable(state)
                    || y == 1 && state.is(Blocks.COBBLESTONE_STAIRS))) throw new SettlementTerrain.Rejected(GenerationDiagnostics.Rejection.PATH);
            before.putIfAbsent(pos, state);
        }
        if (!level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(
                floor.getX(), floor.getY() + 1, floor.getZ(), floor.getX() + 1, floor.getY() + 4, floor.getZ() + 1),
                entity -> !entity.isSpectator()).isEmpty()) throw new SettlementTerrain.Rejected(GenerationDiagnostics.Rejection.ENTITIES);
    }

    private record Cell(int x, int z) {
        static Cell of(BlockPos pos) { return new Cell(pos.getX(), pos.getZ()); }
        int distance(Cell other) { return Math.abs(x - other.x) + Math.abs(z - other.z); }
    }
    private record Node(Cell cell, int priority) {}
}

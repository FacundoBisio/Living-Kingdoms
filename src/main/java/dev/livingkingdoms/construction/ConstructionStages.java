package dev.livingkingdoms.construction;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.settlement.EstablishmentPlacementEvents;
import dev.livingkingdoms.structure.SettlementLayout;
import dev.livingkingdoms.structure.SettlementPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Small deterministic masks of the saved native blueprint. No inventory, entity, or fluid is staged.
 * A transaction owns only the exact previous stage, and stays reversible until its receipt is saved. */
public final class ConstructionStages {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CACHE_LIMIT = 32;
    private static final Map<UUID, Cached> CACHE = new LinkedHashMap<>();
    private ConstructionStages() {}

    public enum Result { APPLIED, UNLOADED, BLOCKED }
    private record Cached(SettlementLayout plan, Blueprint blueprint) {}
    private record Blueprint(List<Map<BlockPos, BlockState>> states, Set<BlockPos> site,
                             Map<BlockPos, CompoundTag> finalBlockEntities) {}

    /** Stage -1 is the legacy reservation marker; stage 4 is the final native export and paths. */
    public static Map<BlockPos, BlockState> expected(SettlementLayout plan, int stage) {
        checkStage(stage);
        return derive(plan).states().get(stage + 1);
    }
    public static Map<BlockPos, BlockState> expected(ConstructionSavedData.Entry entry, int stage) {
        checkStage(stage);
        return cached(entry).states().get(stage + 1);
    }
    /** Immutable snapshots for site, lower structure, walls, roof, and final building. */
    public static List<Map<BlockPos, BlockState>> stages(SettlementLayout plan) {
        return derive(plan).states().subList(1, 6);
    }

    public static Transaction apply(ServerLevel level, ConstructionSavedData.Entry entry,
                                    int currentStage, int targetStage, ServerPlayer actor) {
        return apply(level, entry, currentStage, targetStage, actor, Set.of());
    }

    /** Registered path floors may already have been completed by an earlier queued project.
     * Only exactly matching planned paving/headroom in those registered columns is accepted. */
    public static Transaction apply(ServerLevel level, ConstructionSavedData.Entry entry,
                                    int currentStage, int targetStage, ServerPlayer actor,
                                    Set<BlockPos> registeredPathFloors) {
        return apply(level,entry,currentStage,targetStage,actor,registeredPathFloors,Set.of());
    }
    public static Transaction apply(ServerLevel level, ConstructionSavedData.Entry entry,
                                    int currentStage, int targetStage, ServerPlayer actor,
                                    Set<BlockPos> registeredPathFloors,Set<UUID> workers) {
        checkStage(currentStage); checkStage(targetStage);
        if (currentStage >= targetStage || currentStage == 4)
            throw new IllegalArgumentException("Construction stages must advance");
        var plan = entry.plan();
        if (!level.getServer().isSameThread() || !level.dimension().location().toString().equals(plan.territory().dimension()))
            throw new IllegalStateException("Construction placement requires the owning server thread and dimension");
        var blueprint = cached(entry);
        var previous = blueprint.states().get(currentStage + 1);
        var target = blueprint.states().get(targetStage + 1);
        Set<BlockPos> scope = targetStage == 4 ? plan.before().keySet() : blueprint.site();
        // Check every chunk before reading any world states; none of these queries load a chunk.
        for (var pos : scope) if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
            return new Transaction(level, Result.UNLOADED, null);
        for (var pos : scope) if (!level.getWorldBorder().isWithinBounds(pos)
                || !plan.territory().contains(plan.territory().dimension(), pos.getX(), pos.getZ())
                || actor != null && !level.mayInteract(actor, pos))
            return new Transaction(level, Result.BLOCKED, new EstablishmentPlacementEvents.Rejected());

        Map<BlockPos, BlockState> rollback = new LinkedHashMap<>();
        boolean ownsPrevious = true;
        for (var pos : scope) {
            var current = level.getBlockState(pos);
            rollback.put(pos, current);
            if (level.getBlockEntity(pos) != null || !current.equals(previous.get(pos))) {
                boolean sharedPath = targetStage == 4 && !blueprint.site().contains(pos)
                        && plan.pathBlocks().containsKey(pos) && current.equals(target.get(pos))
                        && registeredColumn(pos, registeredPathFloors) && level.getBlockEntity(pos) == null;
                if (!sharedPath) ownsPrevious = false;
            }
        }
        if (!ownsPrevious) {
            // World chunks and SavedData are separate Minecraft saves. Adopt only a complete exact
            // target after an interrupted save, never a partially placed stage or edited container.
            if (matchesTarget(level, blueprint, target, scope, targetStage))
                return new Transaction(level, Result.APPLIED, null);
            return new Transaction(level, Result.BLOCKED, new IllegalStateException("Construction site changed"));
        }

        // One local spatial query per stage, then intersect only actual writes. A resident inside
        // untouched clearance does not block a harmless stage, but placement never suffocates it.
        var building = plan.buildings().getFirst();
        var box = building.volume();
        var nearby = level.getEntities((Entity) null, new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1), entity -> !entity.isSpectator());
        for (var pos : scope) if (!rollback.get(pos).equals(target.get(pos)) && !target.get(pos).isAir()) {
            var blockBox = new AABB(pos);
            if (nearby.stream().anyMatch(entity -> entity.getBoundingBox().intersects(blockBox)))
                return new Transaction(level, Result.BLOCKED, new SettlementPlacement.Occupied(pos));
        }

        var before = EstablishmentPlacementEvents.capture(level, scope);
        var transaction = new Transaction(level, Result.APPLIED, null);
        transaction.rollback = Map.copyOf(rollback);
        try {
            if (targetStage == 4) {
                // Keep StructureTemplate placement authoritative, including paired blocks and NBT.
                // The original plan is immutable; this transient copy validates our previous stage.
                var adjusted = new SettlementLayout(plan.territory(), plan.style(), plan.buildings(), plan.pathBlocks(), rollback);
                transaction.nativePlacement = SettlementPlacement.apply(level, adjusted,workers);
            } else {
                for (var pos : scope) write(level, pos, target.get(pos));
            }
            EstablishmentPlacementEvents.validate(actor, before);
            return transaction;
        } catch (RuntimeException failure) {
            transaction.close();
            return new Transaction(level, Result.BLOCKED, failure);
        }
    }

    private static boolean registeredColumn(BlockPos position, Set<BlockPos> floors) {
        for (int height = 0; height <= 3; height++) if (floors.contains(position.below(height))) return true;
        return false;
    }
    private static boolean matchesTarget(ServerLevel level, Blueprint blueprint, Map<BlockPos, BlockState> target,
                                         Set<BlockPos> scope, int stage) {
        for (var pos : scope) {
            if (!level.getBlockState(pos).equals(target.get(pos))) return false;
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                if (target.get(pos).hasBlockEntity()) return false;
                continue;
            }
            if (stage != 4 || !target.get(pos).hasBlockEntity()) return false;
            var actual = blockEntity.saveWithFullMetadata(level.registryAccess());
            // RandomizableContainer.isEmpty unpacks its loot table. Inspect its saved tag first
            // so recovery is read-only even when a player has installed a loot-bearing container.
            if (actual.contains("LootTable") || blockEntity instanceof Container container && !container.isEmpty()) return false;
            var expected = blueprint.finalBlockEntities().get(pos);
            if (expected != null) {
                for (String key : expected.getAllKeys()) {
                    if (key.equals("x") || key.equals("y") || key.equals("z")) continue;
                    if (!Objects.equals(expected.get(key), actual.get(key))) return false;
                }
            }
        }
        return true;
    }

    private static Blueprint cached(ConstructionSavedData.Entry entry) {
        var value = CACHE.get(entry.project().id());
        if (value != null && value.plan() == entry.plan()) return value.blueprint();
        var blueprint = derive(entry.plan());
        if (CACHE.size() >= CACHE_LIMIT) CACHE.remove(CACHE.keySet().iterator().next());
        CACHE.put(entry.project().id(), new Cached(entry.plan(), blueprint));
        return blueprint;
    }
    private static Blueprint derive(SettlementLayout plan) {
        plan.validateGeometry();
        if (plan.buildings().size() != 1) throw new IllegalArgumentException("One staged building per project");
        var building = plan.buildings().getFirst();
        var module = building.module();
        Set<BlockPos> site = new HashSet<>(building.supports());
        for (var mutable : BlockPos.betweenClosed(building.origin(), building.high())) site.add(mutable.immutable());
        if (!plan.before().keySet().containsAll(site)) throw new IllegalArgumentException("Incomplete construction snapshot");
        var marker = building.origin().above();
        List<Map<BlockPos, BlockState>> stages = new ArrayList<>();
        Map<BlockPos, BlockState> reservation = new LinkedHashMap<>(plan.before());
        reservation.put(marker, KingdomBlocks.CONSTRUCTION_MARKER.get().defaultBlockState());
        stages.add(Map.copyOf(reservation));

        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (var info : module.blocks()) if (info.nbt() != null)
            blockEntities.put(building.position(info.pos()), info.nbt().copy());
        for (int stage = 0; stage < 4; stage++) {
            Map<BlockPos, BlockState> map = new LinkedHashMap<>(plan.before());
            // Clear only the module's validated volume; supports and paths retain their own bounds.
            for (var pos : site) if (pos.getY() >= building.origin().getY()) map.put(pos, Blocks.AIR.defaultBlockState());
            for (var support : building.supports()) map.put(support, Blocks.COBBLESTONE.defaultBlockState());
            Map<BlockPos, BlockState> candidates = new HashMap<>();
            int height = stage == 1 ? 1 : stage == 2 ? Math.max(2, module.size().getY() - 3) : module.size().getY() - 1;
            for (var info : module.blocks()) {
                var local = info.pos();
                if (stage == 0) {
                    if (local.getY() == 0 && (local.getX() == 0 || local.getZ() == 0
                            || local.getX() == module.size().getX() - 1 || local.getZ() == module.size().getZ() - 1))
                        candidates.put(building.position(local), info.state().rotate(building.rotation()));
                } else if (local.getY() <= height && structural(info.state(), local))
                    candidates.put(building.position(local), info.state().rotate(building.rotation()));
            }
            // Every visible piece must have a face-connected route to native floor. This also
            // defers unsupported decorative fragments in sparse future templates until final.
            Set<BlockPos> connected = connectedToFloor(candidates.keySet(), building.origin().getY());
            connected.forEach(pos -> map.put(pos, candidates.get(pos)));
            if (stage == 0) for (int x = 1; x <= 2; x++)
                map.put(building.position(new BlockPos(x, 1, 0)), Blocks.OAK_LOG.defaultBlockState());
            map.put(marker, KingdomBlocks.CONSTRUCTION_MARKER.get().defaultBlockState());
            stages.add(Map.copyOf(map));
        }
        Map<BlockPos, BlockState> finalMap = new LinkedHashMap<>(plan.before());
        for (var pos : site) if (pos.getY() >= building.origin().getY()) finalMap.put(pos, Blocks.AIR.defaultBlockState());
        for (var support : building.supports()) finalMap.put(support, Blocks.COBBLESTONE.defaultBlockState());
        for (var info : module.blocks()) finalMap.put(building.position(info.pos()), info.state().rotate(building.rotation()));
        finalMap.putAll(plan.pathBlocks());
        stages.add(Map.copyOf(finalMap));
        return new Blueprint(List.copyOf(stages), Set.copyOf(site), Map.copyOf(blockEntities));
    }

    private static boolean structural(BlockState state, BlockPos local) {
        if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()
                || state.getBlock() instanceof BedBlock || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FarmBlock || state.getBlock() instanceof LeavesBlock) return false;
        return Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, local))
                || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock
                || state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock;
    }
    private static Set<BlockPos> connectedToFloor(Set<BlockPos> candidates, int floor) {
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        candidates.stream().filter(pos -> pos.getY() == floor).forEach(todo::add);
        while (!todo.isEmpty()) {
            var pos = todo.removeFirst();
            if (!candidates.contains(pos) || !connected.add(pos)) continue;
            for (var direction : Direction.values()) todo.addLast(pos.relative(direction));
        }
        return connected;
    }
    private static void checkStage(int stage) {
        if (stage < -1 || stage > 4) throw new IllegalArgumentException("Invalid construction stage");
    }
    private static void write(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).equals(state) && (!level.setBlock(pos, state, FLAGS) || !level.getBlockState(pos).equals(state)))
            throw new IllegalStateException("Construction stage write refused at " + pos);
    }

    public static final class Transaction implements AutoCloseable {
        private final ServerLevel level;
        private final Result result;
        private final RuntimeException failure;
        private Map<BlockPos, BlockState> rollback = Map.of();
        private SettlementPlacement nativePlacement;
        private boolean closed;
        private Transaction(ServerLevel level, Result result, RuntimeException failure) {
            this.level = level; this.result = result; this.failure = failure;
        }
        public Result result() { return result; }
        public boolean occupied() { return failure instanceof SettlementPlacement.Occupied; }
        public Optional<RuntimeException> failure() { return Optional.ofNullable(failure); }
        public void commit() {
            if (result != Result.APPLIED || closed) throw new IllegalStateException("Cannot commit unapplied construction");
            if (nativePlacement != null) nativePlacement.commit();
            closed = true;
        }
        @Override public void close() {
            if (closed) return;
            if (nativePlacement != null) nativePlacement.close();
            // Owned intermediate states never have block entities. Final rollback removes only
            // entities created by this transaction after the exact previous-stage preflight.
            for (var pos : rollback.keySet()) if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
            for (var entry : rollback.entrySet()) write(level, entry.getKey(), entry.getValue());
            closed = true;
        }
    }
}

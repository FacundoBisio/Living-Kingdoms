package dev.livingkingdoms.economy;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.ui.ManagementService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import java.util.*;

/**
 * Places one ledger desk inside every kingdom building (Warehouses also get a Kingdom Storage block) and routes
 * desk use to that building's management page. Placement only writes into air with a sturdy floor inside the
 * building's own footprint (or just outside its door for open buildings such as farms). Runs on construction
 * completion and, throttled to once per ten minutes per kingdom, when a member talks to the Mayor.
 */
public final class BuildingHearts {
    private static final Map<UUID, Long> CHECKED = new HashMap<>();
    private BuildingHearts() {}
    public static void clear() { CHECKED.clear(); }

    public static void use(ServerPlayer player, BlockPos pos) {
        var settlement = SettlementSavedData.get(player.server).alliedAt(player.level().dimension().location().toString(), pos.getX(), pos.getZ()).orElse(null);
        var building = settlement == null ? null : at(player.server, settlement, pos);
        if (building == null || !ManagementService.open(player, settlement.id(), building.id()))
            player.displayClientMessage(Component.translatable("ledger.livingkingdoms.unavailable"), true);
    }

    /** The active building whose footprint (plus one block) contains {@code pos}. */
    public static FunctionalBuilding at(net.minecraft.server.MinecraftServer server, Settlement settlement, BlockPos pos) {
        for (var b : ProfessionSavedData.get(server).buildings(settlement.id())) {
            var box = b.bounds();
            if (b.active() && pos.getX() >= box.minX() - 2 && pos.getX() <= box.maxX() + 2 && pos.getZ() >= box.minZ() - 2 && pos.getZ() <= box.maxZ() + 2
                    && Math.abs(pos.getY() - b.origin().getY()) <= 14) return b;
        }
        return null;
    }

    public static void ensureThrottled(ServerLevel level, Settlement settlement) {
        long now = level.getGameTime();
        if (now < CHECKED.getOrDefault(settlement.id(), 0L)) return;
        if (CHECKED.size() > 512) CHECKED.clear();
        CHECKED.put(settlement.id(), now + 12000);
        FoundersLodge.convert(level, settlement);
        ensure(level, settlement);
    }

    /** Places missing desks; returns how many were placed. */
    public static int ensure(ServerLevel level, Settlement settlement) {
        if (!settlement.faction().isAllied() || !settlement.territory().dimension().equals(level.dimension().location().toString())) return 0;
        int placed = 0;
        for (var b : ProfessionSavedData.get(level.getServer()).buildings(settlement.id())) {
            if (!b.active() || b.template().getPath().contains("converted") || b.kind() == BuildingKind.FOUNDING_CAMP && !FoundersLodge.converted(level.getServer(), settlement.id())) continue;
            if (!level.hasChunkAt(b.origin()) || !level.hasChunkAt(new BlockPos(b.bounds().maxX(), b.origin().getY(), b.bounds().maxZ()))) continue;
            if (find(level, b) != null) continue;
            var spot = spot(level, b);
            if (spot == null) continue;
            var facing = Direction.getNearest(b.entrance().getX() - spot.getX(), 0, b.entrance().getZ() - spot.getZ());
            if (facing.getAxis().isVertical()) facing = Direction.SOUTH;
            level.setBlock(spot, KingdomBlocks.BUILDING_LEDGER.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing), Block.UPDATE_ALL);
            placed++;
            if (b.kind() == BuildingKind.WAREHOUSE) {
                for (var dir : Direction.Plane.HORIZONTAL) {
                    var next = spot.relative(dir);
                    if (inside(b, next) && free(level, next)) {
                        level.setBlock(next, KingdomBlocks.KINGDOM_STORAGE.get().defaultBlockState(), Block.UPDATE_ALL);
                        break;
                    }
                }
            }
        }
        return placed;
    }

    /** The desk of this building if one stands inside (or just outside) its footprint. */
    public static BlockPos find(ServerLevel level, FunctionalBuilding b) {
        var box = b.bounds(); int y0 = b.origin().getY();
        for (int y = y0; y <= y0 + 4; y++) for (int x = box.minX() - 2; x <= box.maxX() + 2; x++) for (int z = box.minZ() - 2; z <= box.maxZ() + 2; z++) {
            var pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).is(KingdomBlocks.BUILDING_LEDGER.get())) return pos;
        }
        return null;
    }

    private static boolean inside(FunctionalBuilding b, BlockPos pos) {
        var box = b.bounds();
        return pos.getX() > box.minX() && pos.getX() < box.maxX() && pos.getZ() > box.minZ() && pos.getZ() < box.maxZ();
    }
    private static boolean free(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP) && level.getBlockEntity(pos) == null;
    }

    /** Interior spot against a wall, off the door line; otherwise beside the doorstep, outside the footprint. */
    static BlockPos spot(ServerLevel level, FunctionalBuilding b) {
        var box = b.bounds(); int y0 = b.origin().getY(); var door = b.entrance();
        var candidates = new ArrayList<BlockPos>();
        for (int y = y0 + 1; y <= y0 + 2; y++) for (int x = box.minX() + 1; x < box.maxX(); x++) for (int z = box.minZ() + 1; z < box.maxZ(); z++) {
            if (x == door.getX() || z == door.getZ()) continue;
            var pos = new BlockPos(x, y, z);
            if (free(level, pos)) candidates.add(pos);
        }
        var wall = candidates.stream().filter(p -> Direction.Plane.HORIZONTAL.stream().anyMatch(d -> level.getBlockState(p.relative(d)).isSolid())).toList();
        var pool = wall.isEmpty() ? candidates : wall;
        var cx = (box.minX() + box.maxX()) / 2.0; var cz = (box.minZ() + box.maxZ()) / 2.0;
        var best = pool.stream().min(Comparator.comparingDouble((BlockPos p) -> Math.abs(p.getY() - y0 - 1) * 100 + Math.hypot(p.getX() - cx, p.getZ() - cz))).orElse(null);
        if (best != null) return best;
        // Open buildings (farms): beside the doorstep, two blocks to either side.
        var outward = Direction.getNearest(door.getX() - cx, 0, door.getZ() - cz);
        for (int side : new int[]{2, -2, 3, -3}) for (int dy = 1; dy >= -1; dy--) {
            var pos = door.relative(outward.getClockWise(), side).above(dy);
            if (free(level, pos)) return pos;
        }
        return null;
    }
}

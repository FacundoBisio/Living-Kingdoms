package dev.livingkingdoms.rpg;

import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.SettlementSafety;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.GuardWork;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.WallDesign;
import dev.livingkingdoms.rpg.domain.WallGeometry;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/**
 * Kingdom walls: a ring of columns just inside the territory border, raised gradually (four columns a second)
 * from Kingdom Storage. Paths crossing the ring get doors, every {@link Fortification#POST_SPACING}th column is
 * a flat archer post. Hostile invaders can break pieces while the kingdom is threatened; once it is safe, broken
 * pieces are repaired from storage. Only air, plants, leaves, column logs, shallow water and our own pieces (wall
 * material on the wall footprint) are written. Breach bookkeeping, rebuild and sweep cursors are transient (bounded per
 * kingdom) and forgotten across restarts; the repair {@link #sweep} re-derives damage from the world, so nothing is lost.
 * Rebuild cost: ~19 µs per column measured (radius 96 = 736 columns ≈ 14 ms), so a rebuild handles at most
 * {@link #REBUILD_COLUMNS} columns per tick and a sweep {@link #SWEEP_COLUMNS} every {@link #REPAIR_TICKS} ticks.
 * When the territory grows, the next order moves the wall: the persisted old radius is demolished
 * {@link #DEMOLISH_COLUMNS} columns per step (no sweep meanwhile) and half its cost is refunded.
 */
public final class WallService {
    public static final int COLUMNS_PER_STEP = 1, STEP_TICKS = 5, SIEGE_TICKS = 40, REPAIR_TICKS = 100, MAX_BREACHES = 256, REBUILD_COLUMNS = 384, SWEEP_COLUMNS = 16, DEMOLISH_COLUMNS = 4;
    static final int GROUND_SCAN = 24, MAX_LOGS = 12, MAX_WATER = 8, SLOPE_CAP = 3, SPAN = 14;
    private static final Map<UUID, LinkedHashMap<BlockPos, BlockState>> BREACHES = new HashMap<>();
    /** Next ring index of a rebuild still running for a kingdom (transient; finished or forgotten on restart). */
    private static final Map<UUID, Integer> REBUILDS = new HashMap<>();
    /** Next ring index of each kingdom's repair sweep (transient; a restart simply starts the walk again). */
    private static final Map<UUID, Integer> SWEEPS = new HashMap<>();
    // shortcut: transient, so columns skipped before a restart are charged by the sweep; persist it if players notice.
    /** Ring columns the tier price covered but an unloaded chunk skipped; the sweep raises them without charging again. */
    private static final Map<UUID, java.util.BitSet> PREPAID = new HashMap<>();
    /** Old-ring demolition pass of each moved wall: {cursor, dirty} (transient; a restart walks again, stripped columns are no-ops). */
    private static final Map<UUID, int[]> DEMOLITIONS = new HashMap<>();
    /** Blocks put() has placed, and the count it may not exceed (a sweep sets it to what Kingdom Storage can pay). */
    private static int placed, budget = Integer.MAX_VALUE;
    private static final Map<UUID, PostCache> POSTS = new HashMap<>();
    private record PostCache(long at, int built, List<BlockPos> posts) {}
    private WallService() {}

    public static int radiusFor(Settlement settlement) { return Math.clamp(settlement.territory().radius() - 3, 8, 96); }

    /** Ordered, 8-connected ring offsets (deterministic for a radius, so progress can resume from an index). */
    public static List<int[]> ring(int radius) {
        var keys = new LinkedHashSet<Long>();
        var out = new ArrayList<int[]>();
        int steps = (int) Math.ceil(2 * Math.PI * radius * 4);
        for (int i = 0; i < steps; i++) {
            double a = 2 * Math.PI * i / steps;
            int dx = (int) Math.round(radius * Math.cos(a)), dz = (int) Math.round(radius * Math.sin(a));
            if (keys.add(((long) dx << 32) ^ (dz & 0xffffffffL))) out.add(new int[]{dx, dz});
        }
        return out;
    }

    /** Whether the standing wall no longer sits on the border (the territory grew), so the next order moves it. */
    public static boolean misplaced(MinecraftServer server, Settlement settlement) {
        var wall = RpgSavedData.get(server).wall(settlement.id()).orElse(null);
        return wall != null && wall.tier() > 0 && wall.radius() != radiusFor(settlement);
    }

    /** Why a wall cannot be raised (or upgraded) right now, or empty when it can. */
    public static Optional<String> blocked(MinecraftServer server, Settlement settlement) {
        if (!settlement.faction().isAllied() || settlement.lifecycle() != SettlementLifecycle.ESTABLISHED) return Optional.of("wall.livingkingdoms.not_ready");
        var data = RpgSavedData.get(server); var wall = data.wall(settlement.id()).orElse(null);
        if (wall != null && (!wall.complete() || REBUILDS.containsKey(settlement.id()) || data.oldRadius(settlement.id()).isPresent())) return Optional.of("wall.livingkingdoms.building");
        if (wall != null && wall.tier() >= Fortification.MAX_TIER && !misplaced(server, settlement)) return Optional.of("wall.livingkingdoms.max");
        if (DefenseService.status(server, settlement.id()) == SettlementSafety.THREATENED) return Optional.of("wall.livingkingdoms.threatened");
        return Optional.empty();
    }

    public static int tier(MinecraftServer server, UUID settlement) { return RpgSavedData.get(server).wall(settlement).map(Fortification::tier).orElse(0); }

    public static Map<StockResource, Integer> nextCost(MinecraftServer server, Settlement settlement) {
        int next = Math.min(Fortification.MAX_TIER, tier(server, settlement.id()) + 1);
        var cost = Fortification.cost(next, radiusFor(settlement));
        var map = new EnumMap<StockResource, Integer>(StockResource.class);
        if (cost[0] > 0) map.put(StockResource.WOOD, cost[0]);
        if (cost[1] > 0) map.put(StockResource.STONE, cost[1]);
        if (cost[2] > 0) map.put(StockResource.IRON, cost[2]);
        return map;
    }

    /** Pays the next tier from Kingdom Storage and starts raising it. */
    public static boolean fortify(ServerPlayer player, UUID settlementId) {
        var settlement = SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        if (settlement == null || !KingdomEconomy.authorized(player, settlementId) || !KingdomOnboardingService.canHelp(player, settlement)) return false;
        var reason = blocked(player.server, settlement);
        if (reason.isPresent()) { player.displayClientMessage(Component.translatable(reason.get()), true); return false; }
        var payment = KingdomEconomy.plan(player.server, settlementId, nextCost(player.server, settlement), Map.of(), false).orElse(null);
        if (payment == null || !KingdomEconomy.valid(player.server, payment)) {
            player.displayClientMessage(Component.translatable("wall.livingkingdoms.no_stock"), true); return false;
        }
        var data = RpgSavedData.get(player.server);
        var current = data.wall(settlementId).orElse(Fortification.none(radiusFor(settlement)));
        boolean moving = misplaced(player.server, settlement);
        // A tier-3 wall off the border is moved at the same tier; putWall records the old ring for demolition.
        var next = current.tier() >= Fortification.MAX_TIER ? new Fortification(current.tier(), radiusFor(settlement), 0, false)
                : new Fortification(current.tier(), radiusFor(settlement), 0, true).upgrade();
        if (!KingdomEconomy.commit(player.server, payment)) return false;
        data.putWall(settlementId, next);
        POSTS.remove(settlementId); PREPAID.remove(settlementId); // a new ring or tier re-marks what it skips
        if (current.tier() < Fortification.MAX_TIER) player.sendSystemMessage(Component.translatable("wall.livingkingdoms.started",
                Component.translatable("wall.livingkingdoms.tier." + next.tier())).withStyle(ChatFormatting.GOLD));
        if (moving) {
            player.sendSystemMessage(Component.translatable("wall.livingkingdoms.moving").withStyle(ChatFormatting.GOLD));
            // Half of what the old ring cost is salvaged into Kingdom Storage; a refund that does not fit is dropped.
            int[] old = Fortification.cost(current.tier(), current.radius());
            var salvage = new EnumMap<StockResource, Integer>(StockResource.class);
            if (old[0] / 2 > 0) salvage.put(StockResource.WOOD, old[0] / 2);
            if (old[1] / 2 > 0) salvage.put(StockResource.STONE, old[1] / 2);
            if (old[2] / 2 > 0) salvage.put(StockResource.IRON, old[2] / 2);
            var refund = KingdomEconomy.plan(player.server, settlementId, Map.of(), salvage, false).orElse(null);
            if (refund != null && KingdomEconomy.commit(player.server, refund)) player.sendSystemMessage(Component.translatable("wall.livingkingdoms.salvaged",
                    old[0] / 2, old[1] / 2, old[2] / 2).withStyle(ChatFormatting.GRAY));
        }
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, .8F, .9F);
        return true;
    }

    /** Test/command helper: raises the whole current ring at once (still respecting what may be overwritten). */
    public static int buildAll(ServerLevel level, UUID settlementId) {
        int placed = 0;
        for (int guard = 0; guard < 4096; guard++) {
            var wall = RpgSavedData.get(level.getServer()).wall(settlementId).orElse(null);
            if (wall == null || wall.complete()) break;
            if (!step(level, settlementId)) break;
            placed++;
        }
        demolishAll(level, settlementId);
        return placed;
    }

    /** Test/command helper: takes the whole old ring down at once (bounded passes; unloaded columns stay pending). */
    public static void demolishAll(ServerLevel level, UUID id) {
        var data = RpgSavedData.get(level.getServer());
        for (int pass = 0; pass < 4 && data.oldRadius(id).isPresent(); pass++) demolish(level, id, 4096);
    }

    /**
     * Takes down up to {@code columns} columns of the old ring left by a move, walking a transient cursor over the old
     * geometry: our pieces (position and material) from the real ground up, tower cells and hung banners included.
     * Cells the current wall owns, columns inside buildings and unloaded columns are left alone (unloaded ones are
     * retried); a full pass that removes and skips nothing ends the demolition. Returns the blocks removed.
     */
    static int demolish(ServerLevel level, UUID id, int columns) {
        var data = RpgSavedData.get(level.getServer());
        var old = data.oldRadius(id).orElse(null); var wall = data.wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (old == null || wall == null || settlement == null) { DEMOLITIONS.remove(id); return 0; }
        var design = data.design(id); var geo = WallGeometry.of(old, design); var keep = WallGeometry.of(wall.radius(), design); var t = settlement.territory();
        int[] pass = DEMOLITIONS.computeIfAbsent(id, k -> new int[2]); // {cursor, removed or skipped something this pass}
        int removed = 0;
        for (int done = 0; done < columns && pass[0] < geo.size(); done++, pass[0]++) {
            int[] o = geo.ring().get(pass[0]);
            if (!loaded(level, new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]))) { pass[1] = 1; continue; }
            int reach = design.towers() && geo.towerCenters().contains(pass[0]) ? 1 : 0;
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                int ox = o[0] + dx, oz = o[1] + dz, x = t.x() + ox, z = t.z() + oz;
                if (keep.ownsAnyDesign(ox, oz) || insideBuilding(level, settlement, new BlockPos(x, t.y(), z))) continue;
                int g = ground(level, x, z, true);
                removed += strip(level, settlement, geo, x, z, g, g);
            }
        }
        if (removed > 0) { pass[1] = 1; POSTS.remove(id); }
        if (pass[0] >= geo.size()) {
            if (pass[1] == 0) { data.clearOldRadius(id); DEMOLITIONS.remove(id); }
            else { pass[0] = 0; pass[1] = 0; }
        }
        return removed;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % STEP_TICKS == 0) for (var id : RpgSavedData.get(server).walledSettlements()) {
            var wall = RpgSavedData.get(server).wall(id).orElse(null);
            if (wall == null || wall.complete()) continue;
            var level = level(server, id);
            if (level != null) for (int i = 0; i < COLUMNS_PER_STEP; i++) if (!step(level, id)) break;
        }
        if (now % STEP_TICKS == 0) for (var id : RpgSavedData.get(server).relocating()) {
            var level = level(server, id);
            if (level != null) demolish(level, id, DEMOLISH_COLUMNS);
        }
        if (!REBUILDS.isEmpty()) for (var id : List.copyOf(REBUILDS.keySet())) {
            var level = level(server, id);
            if (level == null) REBUILDS.remove(id); else continueRebuild(level, id);
        }
        if (now % SIEGE_TICKS == 7) for (var id : RpgSavedData.get(server).walledSettlements()) {
            if (DefenseService.status(server, id) != SettlementSafety.THREATENED) continue;
            var level = level(server, id);
            if (level != null) siege(level, id);
        }
        if (now % REPAIR_TICKS == 13 && !BREACHES.isEmpty()) for (var id : List.copyOf(BREACHES.keySet())) {
            if (DefenseService.status(server, id) == SettlementSafety.THREATENED) continue;
            var level = level(server, id);
            if (level != null) repair(level, id, 8);
        }
        if (now % REPAIR_TICKS == 63) for (var id : RpgSavedData.get(server).walledSettlements()) {
            var level = level(server, id);
            if (level != null) sweep(level, id, SWEEP_COLUMNS);
        }
    }

    private static ServerLevel level(MinecraftServer server, UUID id) {
        var settlement = SettlementSavedData.get(server).get(id).orElse(null);
        if (settlement == null || !settlement.faction().isAllied()) return null;
        var dimension = ResourceLocation.tryParse(settlement.territory().dimension());
        return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    /** Advances one column (placing it when its chunks are loaded, else leaving it to the sweep); false when nothing remains. */
    static boolean step(ServerLevel level, UUID id) {
        var data = RpgSavedData.get(level.getServer());
        var wall = data.wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || wall.complete() || settlement == null) return false;
        var geo = WallGeometry.of(wall.radius(), data.design(id));
        if (wall.built() >= geo.size()) { finish(level, settlement, wall); return false; }
        var t = settlement.territory();
        int[] offset = geo.ring().get(wall.built());
        // Never wait for a chunk: a large ring with low simulation distance would stay "building" forever.
        if (loaded(level, new BlockPos(t.x() + offset[0], t.y(), t.z() + offset[1]))) column(level, settlement, geo, wall.tier(), wall.built());
        else prepaid(id, wall.built());
        var next = wall.progress(1);
        if (next.built() >= geo.size()) { data.putWall(id, next); finish(level, settlement, next); }
        else data.putWall(id, next);
        return true;
    }

    private static void finish(ServerLevel level, Settlement settlement, Fortification wall) {
        RpgSavedData.get(level.getServer()).putWall(settlement.id(), wall.finished());
        // A new tier also re-dresses the towers and gatehouses in the new material.
        var design = RpgSavedData.get(level.getServer()).design(settlement.id());
        if (design.towers()) towers(level, settlement.id());
        if (design.gatehouses()) gatehouses(level, settlement.id());
        POSTS.remove(settlement.id());
        var t = settlement.territory();
        var center = new BlockPos(t.x(), t.y(), t.z());
        level.playSound(null, center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.BLOCKS, 1F, 1F);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, center.getX() + .5, center.getY() + 3, center.getZ() + .5, 60, 3, 2, 3, .3);
        for (var player : level.players()) if (t.contains(t.dimension(), player.getBlockX(), player.getBlockZ()))
            player.sendSystemMessage(Component.translatable("wall.livingkingdoms.finished", VillageNames.display(settlement),
                    Component.translatable("wall.livingkingdoms.tier." + wall.tier())).withStyle(ChatFormatting.GREEN));
    }

    public static BlockState body(int tier) {
        return switch (tier) { case 1 -> Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(); case 2 -> Blocks.COBBLESTONE.defaultBlockState(); default -> Blocks.STONE_BRICKS.defaultBlockState(); };
    }
    public static BlockState cap(int tier) {
        return switch (tier) { case 1 -> Blocks.SPRUCE_FENCE.defaultBlockState(); case 2 -> Blocks.COBBLESTONE_WALL.defaultBlockState(); default -> Blocks.STONE_BRICK_WALL.defaultBlockState(); };
    }
    /** Blocks any tier of our wall is made of (so upgrades and repairs may replace them). */
    public static boolean wallBlock(BlockState state) {
        return state.is(Blocks.STRIPPED_SPRUCE_LOG) || state.is(Blocks.SPRUCE_FENCE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_WALL)
                || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.STONE_BRICK_WALL);
    }
    /** Any piece a wall places: wall blocks, gate doors, tower lanterns and gatehouse banners. Material only. */
    private static boolean piece(BlockState state) {
        return wallBlock(state) || state.getBlock() instanceof DoorBlock || state.is(Blocks.LANTERN) || state.is(Blocks.BLUE_WALL_BANNER);
    }
    private static boolean onFootprint(Settlement s, WallGeometry geo, BlockPos pos) { return geo.ownsAnyDesign(pos.getX() - s.territory().x(), pos.getZ() - s.territory().z()); }
    /** Ownership is position AND material: a wall-material block next to the ring (a house, a lamp post) is never ours. */
    private static boolean ours(Settlement s, WallGeometry geo, BlockPos pos, BlockState state) {
        return wallBlock(state) && geo.owns(pos.getX() - s.territory().x(), pos.getZ() - s.territory().z());
    }
    /** Plants, grass, torches, carpets, thin snow: no collision (carpets aside), no fluid, no block entity. */
    private static boolean plant(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && (state.getCollisionShape(level, pos).isEmpty() || state.is(BlockTags.WOOL_CARPETS))
                && level.getBlockEntity(pos) == null;
    }
    private static boolean water(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(level, pos).isEmpty() && level.getBlockEntity(pos) == null;
    }
    private static boolean natural(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK);
    }
    private static boolean writable(ServerLevel level, Settlement s, WallGeometry geo, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (piece(state)) return onFootprint(s, geo, pos);
        if (level.getBlockEntity(pos) != null) return false;
        return state.isAir() || state.is(BlockTags.LEAVES) || plant(level, pos, state) || water(level, pos, state) || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    /**
     * First free Y of a column: from the MOTION_BLOCKING heightmap down (at most {@link #GROUND_SCAN}) through air, leaves,
     * logs, plants and our banners, and — on our own footprint — through our own pieces. Stops at the first solid block
     * or fluid, so it reads the same Y before and after the wall stands (a lintel is never taken for ground).
     */
    static int ground(ServerLevel level, int x, int z, boolean owned) {
        var pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        for (int i = 0; i < GROUND_SCAN; i++) {
            pos.move(Direction.DOWN);
            var state = level.getBlockState(pos);
            if (!(state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(Blocks.BLUE_WALL_BANNER)
                    || plant(level, pos, state) || owned && piece(state))) return pos.getY() + 1;
        }
        return pos.getY();
    }

    /** Ground of the outside neighbours (natural terrain or water only), capped so a cliff does not tower the wall. */
    private static int outsideGround(ServerLevel level, Settlement s, WallGeometry geo, int dx, int dz, int own) {
        var t = s.territory(); int high = own; long r2 = (long) dx * dx + (long) dz * dz;
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            int nx = dx + ox, nz = dz + oz;
            if ((long) nx * nx + (long) nz * nz <= r2 || geo.ownsAnyDesign(nx, nz)) continue;
            var col = new BlockPos(t.x() + nx, own, t.z() + nz);
            if (!level.hasChunkAt(col)) continue;
            int g = ground(level, col.getX(), col.getZ(), false);
            var floor = level.getBlockState(new BlockPos(col.getX(), g - 1, col.getZ()));
            if (!floor.getFluidState().isEmpty()) high = Math.max(high, Math.min(g, own + MAX_WATER));
            else if (natural(floor)) high = Math.max(high, Math.min(g, own + SLOPE_CAP));
        }
        return high;
    }

    /** Clears plants in {@code [from, to]} and up to {@link #MAX_LOGS} logs above {@code from}; logs are refunded as Wood. */
    private static void clearColumn(ServerLevel level, Settlement s, int x, int z, int from, int to) {
        int logs = 0;
        for (int y = from; y <= from + SPAN && logs < MAX_LOGS; y++) {
            var pos = new BlockPos(x, y, z); var state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS) && !piece(state) && level.getBlockEntity(pos) == null) { level.removeBlock(pos, false); logs++; }
            else if (y <= to && plant(level, pos, state)) level.removeBlock(pos, false);
        }
        if (logs == 0) return;
        var refund = KingdomEconomy.plan(level.getServer(), s.id(), Map.of(), Map.of(StockResource.WOOD, logs), false).orElse(null);
        if (refund != null) KingdomEconomy.commit(level.getServer(), refund);
    }

    /** Removes our pieces (and banners hanging on them) from {@code from} up to {@code ground + SPAN + 2}. */
    private static int strip(ServerLevel level, Settlement s, WallGeometry geo, int x, int z, int from, int ground) {
        int removed = 0;
        for (int y = from; y <= ground + SPAN + 2; y++) {
            var pos = new BlockPos(x, y, z);
            if (!piece(level.getBlockState(pos)) || !onFootprint(s, geo, pos)) continue;
            for (var dir : Direction.Plane.HORIZONTAL) {
                var side = pos.relative(dir); var state = level.getBlockState(side);
                if (state.is(Blocks.BLUE_WALL_BANNER) && state.getValue(WallBannerBlock.FACING) == dir) level.removeBlock(side, false);
            }
            level.removeBlock(pos, false);
            removed++;
        }
        return removed;
    }

    private static boolean gate(ServerLevel level, Settlement settlement, BlockPos floor) {
        if (level.getBlockState(floor).is(Blocks.DIRT_PATH) || level.getBlockState(floor.above()).getBlock() instanceof DoorBlock) return true;
        var layout = SettlementSavedData.get(level.getServer()).layout(settlement.id()).orElse(null);
        if (layout == null) return false;
        for (var p : layout.paths()) if (p.getX() == floor.getX() && p.getZ() == floor.getZ() && Math.abs(p.getY() - floor.getY()) <= 3) return true;
        return false;
    }

    private static boolean insideBuilding(ServerLevel level, Settlement settlement, BlockPos column) {
        var layout = SettlementSavedData.get(level.getServer()).layout(settlement.id()).orElse(null);
        if (layout == null) return false;
        for (var b : layout.buildings()) {
            var box = b.bounds();
            if (column.getX() >= box.minX() - 1 && column.getX() <= box.maxX() + 1 && column.getZ() >= box.minZ() - 1 && column.getZ() <= box.maxZ() + 1) return true;
        }
        return false;
    }

    /** Whether a column and its eight neighbours are loaded (outsideGround reads them; a half-loaded edge lowers the wall). */
    private static boolean loaded(ServerLevel level, BlockPos column) {
        return level.hasChunkAt(column.offset(-1, 0, -1)) && level.hasChunkAt(column.offset(1, 0, -1))
                && level.hasChunkAt(column.offset(-1, 0, 1)) && level.hasChunkAt(column.offset(1, 0, 1));
    }

    /** Raises ring column {@code index} on its real ground (anything of ours above the expected height is removed); returns the blocks placed. */
    static int column(ServerLevel level, Settlement settlement, WallGeometry geo, int tier, int index) {
        int before = placed;
        raiseColumn(level, settlement, geo, tier, index);
        return placed - before;
    }
    private static void raiseColumn(ServerLevel level, Settlement settlement, WallGeometry geo, int tier, int index) {
        int[] offset = geo.ring().get(index);
        var t = settlement.territory();
        int x = t.x() + offset[0], z = t.z() + offset[1];
        var column = new BlockPos(x, t.y(), z);
        if (insideBuilding(level, settlement, column)) return;
        int g = ground(level, x, z, true), bed = g;
        var floor = new BlockPos(x, g - 1, z);
        if (!level.getBlockState(floor).getFluidState().isEmpty()) {
            // Water: the wall body goes down to the bed; deeper than MAX_WATER (or lava) is skipped.
            while (bed > g - MAX_WATER - 1 && water(level, new BlockPos(x, bed - 1, z), level.getBlockState(new BlockPos(x, bed - 1, z)))) bed--;
            var under = level.getBlockState(new BlockPos(x, bed - 1, z));
            if (bed == g || bed <= g - MAX_WATER - 1 || !under.getFluidState().isEmpty()) return;
        }
        var at = new BlockPos(x, g, z);
        boolean raised = geo.raised(offset[0], offset[1]);
        if (geo.gate(index)) {
            // A main gate: a three-high opening under a lintel, wide enough for carts and horses.
            clearColumn(level, settlement, x, z, g, g + 3);
            for (int dy = 0; dy <= 2; dy++) if (piece(level.getBlockState(at.above(dy)))) level.removeBlock(at.above(dy), false);
            put(level, settlement, geo, at.above(3), body(tier));
            if (!raised) strip(level, settlement, geo, x, z, g + (geo.design().gatehouses() ? 5 : 4), g);
            return;
        }
        if (gate(level, settlement, floor)) {
            clearColumn(level, settlement, x, z, g, g + 2);
            var facing = Direction.getNearest(offset[0], 0, offset[1]);
            if (!(level.getBlockState(at).getBlock() instanceof DoorBlock) && writable(level, settlement, geo, at) && writable(level, settlement, geo, at.above())) {
                var door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing);
                level.setBlock(at, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                level.setBlock(at.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            put(level, settlement, geo, at.above(2), body(tier));
            if (!raised) strip(level, settlement, geo, x, z, g + 3, g);
            return;
        }
        boolean post = index % Fortification.POST_SPACING == 0;
        int top = outsideGround(level, settlement, geo, offset[0], offset[1], g) + 2;
        clearColumn(level, settlement, x, z, g, top);
        int mark = placed;
        for (int y = bed; y < top; y++) put(level, settlement, geo, new BlockPos(x, y, z), body(tier));
        put(level, settlement, geo, new BlockPos(x, top, z), post ? body(tier) : cap(tier));
        if (!raised) strip(level, settlement, geo, x, z, top + 1, g);
        if (placed == mark) return; // an intact column stays silent (sweeps and rebuilds pass over it)
        if (level.random.nextInt(3) == 0) level.sendParticles(ParticleTypes.CLOUD, at.getX() + .5, at.getY() + 1.5, at.getZ() + .5, 3, .3, .6, .3, 0);
        if (index % 4 == 0) level.playSound(null, at, tier == 1 ? SoundEvents.WOOD_PLACE : SoundEvents.STONE_PLACE, SoundSource.BLOCKS, .7F, 1F);
    }

    private static void put(ServerLevel level, Settlement s, WallGeometry geo, BlockPos pos, BlockState state) {
        if (placed >= budget || !writable(level, s, geo, pos)) return;
        if (level.getBlockState(pos).is(state.getBlock())) return;
        level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
        placed++;
    }

    /** Standing spots on top of the wall's archer posts (cached; recomputed when the wall changes). */
    public static List<BlockPos> posts(ServerLevel level, Settlement settlement) {
        var wall = RpgSavedData.get(level.getServer()).wall(settlement.id()).orElse(null);
        if (wall == null || wall.tier() == 0) return List.of();
        long now = level.getGameTime();
        var cached = POSTS.get(settlement.id());
        if (cached != null && cached.built == wall.built() && now - cached.at < 600) return cached.posts;
        var geo = WallGeometry.of(wall.radius(), RpgSavedData.get(level.getServer()).design(settlement.id()));
        var ring = geo.ring();
        var t = settlement.territory();
        var posts = new ArrayList<BlockPos>();
        int limit = wall.complete() ? ring.size() : Math.min(ring.size(), wall.built());
        posts.addAll(towerPosts(level, settlement, geo));
        for (int i = 0; i < limit && posts.size() < 36; i += Fortification.POST_SPACING) {
            var column = new BlockPos(t.x() + ring.get(i)[0], t.y(), t.z() + ring.get(i)[1]);
            if (!level.hasChunkAt(column)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
            var stand = new BlockPos(column.getX(), top, column.getZ());
            var under = level.getBlockState(stand.below());
            if (ours(settlement, geo, stand.below(), under) && !under.is(Blocks.SPRUCE_FENCE) && !under.is(Blocks.COBBLESTONE_WALL) && !under.is(Blocks.STONE_BRICK_WALL)
                    && level.getBlockState(stand).isAir() && level.getBlockState(stand.above()).isAir()) posts.add(stand);
        }
        POSTS.put(settlement.id(), new PostCache(now, wall.built(), List.copyOf(posts)));
        return List.copyOf(posts);
    }

    /** Hostile invaders hugging the wall may knock pieces out (15% per piece check every two seconds). */
    static void siege(ServerLevel level, UUID id) {
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        if (settlement == null || wall == null || wall.tier() == 0) return;
        var t = settlement.territory();
        double r = wall.radius();
        var box = new AABB(t.x() - r - 4, t.y() - 24, t.z() - r - 4, t.x() + r + 5, t.y() + 40, t.z() + r + 5);
        var attackers = level.getEntitiesOfClass(Mob.class, box, m -> GuardWork.hostile(m)
                && Math.abs(Math.hypot(m.getX() - t.x() - .5, m.getZ() - t.z() - .5) - r) <= 2.5);
        var geo = WallGeometry.of(wall.radius(), RpgSavedData.get(level.getServer()).design(id));
        int checks = 0;
        for (var mob : attackers) {
            if (++checks > 24) break;
            for (var dir : Direction.Plane.HORIZONTAL) for (int dy = 0; dy <= 1; dy++) {
                var pos = mob.blockPosition().relative(dir).above(dy);
                var state = level.getBlockState(pos);
                if (!ours(settlement, geo, pos, state) || level.random.nextFloat() >= .15F) continue;
                var breaches = BREACHES.computeIfAbsent(id, k -> new LinkedHashMap<>());
                if (breaches.size() >= MAX_BREACHES) return;
                breaches.put(pos.immutable(), state);
                level.destroyBlock(pos, false, mob);
                level.playSound(null, pos, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, .8F, 1F);
                POSTS.remove(id);
            }
        }
    }

    public static int breaches(UUID settlement) { var b = BREACHES.get(settlement); return b == null ? 0 : b.size(); }

    /** Restores up to {@code limit} broken pieces, paying one Wood or Stone each from Kingdom Storage. */
    public static int repair(ServerLevel level, UUID id, int limit) {
        var breaches = BREACHES.get(id);
        if (breaches == null || breaches.isEmpty()) { BREACHES.remove(id); return 0; }
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        if (settlement == null || wall == null) { BREACHES.remove(id); return 0; }
        var geo = WallGeometry.of(wall.radius(), RpgSavedData.get(level.getServer()).design(id));
        int repaired = 0;
        BlockPos last = null;
        var it = breaches.entrySet().iterator();
        while (it.hasNext() && repaired < limit) {
            var entry = it.next();
            var pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            if (!onFootprint(settlement, geo, pos) || !writable(level, settlement, geo, pos) || wallBlock(level.getBlockState(pos))) { it.remove(); continue; }
            var resource = entry.getValue().is(Blocks.STRIPPED_SPRUCE_LOG) || entry.getValue().is(Blocks.SPRUCE_FENCE) ? StockResource.WOOD : StockResource.STONE;
            var payment = KingdomEconomy.plan(level.getServer(), id, Map.of(resource, 1), Map.of(), false).orElse(null);
            if (payment == null || !KingdomEconomy.valid(level.getServer(), payment) || !KingdomEconomy.commit(level.getServer(), payment)) break;
            level.setBlock(pos, Block.updateFromNeighbourShapes(entry.getValue(), level, pos), Block.UPDATE_ALL);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 4, .3, .3, .3, 0);
            it.remove();
            last = pos;
            repaired++;
        }
        if (last != null) level.playSound(null, last, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, .6F, 1.2F);
        if (breaches.isEmpty()) { BREACHES.remove(id); POSTS.remove(id); }
        return repaired;
    }

    /** Test helper: records a breach as if an invader had broken that piece. */
    public static void breach(ServerLevel level, UUID id, BlockPos pos) {
        var state = level.getBlockState(pos);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        if (settlement == null || wall == null || !ours(settlement, WallGeometry.of(wall.radius(), RpgSavedData.get(level.getServer()).design(id)), pos, state)) return;
        BREACHES.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(pos.immutable(), state);
        level.removeBlock(pos, false);
    }

    /**
     * Repair by diff, the path that survives restarts: walks a per-kingdom cursor over a complete ring, re-applying up to
     * {@code columns} loaded columns and paying one Wood (tier 1) or Stone per block placed; when the cursor wraps, towers
     * and gatehouses are re-raised the same way. An intact wall costs block reads only. Returns the blocks placed.
     */
    public static int sweep(ServerLevel level, UUID id, int columns) {
        var server = level.getServer(); var data = RpgSavedData.get(server);
        var wall = data.wall(id).orElse(null);
        var settlement = SettlementSavedData.get(server).get(id).orElse(null);
        if (wall == null || settlement == null || wall.tier() == 0 || !wall.complete() || REBUILDS.containsKey(id) || data.oldRadius(id).isPresent()
                || DefenseService.status(server, id) == SettlementSafety.THREATENED) { SWEEPS.remove(id); return 0; }
        var resource = wall.tier() == 1 ? StockResource.WOOD : StockResource.STONE;
        var design = data.design(id); var geo = WallGeometry.of(wall.radius(), design); var t = settlement.territory();
        int cursor = Math.min(SWEEPS.getOrDefault(id, 0), geo.size()), done = 0, start = placed;
        try {
            for (; done < columns && cursor < geo.size(); cursor++) {
                int[] o = geo.ring().get(cursor);
                // Towers and gatehouses own their raised cells; they are re-raised when the cursor wraps.
                if (geo.raised(o[0], o[1]) || !loaded(level, new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]))) continue;
                var paid = PREPAID.get(id); boolean free = paid != null && paid.get(cursor);
                int stock = KingdomEconomy.count(server, id, resource);
                if (!free && stock <= 0) break; // cannot pay: resume at this column next pass
                budget = free ? Integer.MAX_VALUE : placed + stock;
                int blocks = column(level, settlement, geo, wall.tier(), cursor);
                if (free) paid.clear(cursor); else charge(server, id, resource, blocks);
                done++;
            }
            if (cursor >= geo.size()) {
                budget = placed + KingdomEconomy.count(server, id, resource);
                int before = placed;
                if (design.towers()) towers(level, id);
                if (design.gatehouses()) gatehouses(level, id);
                charge(server, id, resource, placed - before);
                cursor = 0;
            }
        } finally { budget = Integer.MAX_VALUE; }
        SWEEPS.put(id, cursor);
        if (placed > start) POSTS.remove(id);
        return placed - start;
    }
    private static void charge(MinecraftServer server, UUID id, StockResource resource, int blocks) {
        if (blocks <= 0) return;
        var payment = KingdomEconomy.plan(server, id, Map.of(resource, blocks), Map.of(), false).orElse(null);
        if (payment != null && KingdomEconomy.valid(server, payment)) KingdomEconomy.commit(server, payment);
    }

    /** Marks a ring column as already paid for by the tier price (also a test helper). */
    public static void prepaid(UUID id, int column) { PREPAID.computeIfAbsent(id, k -> new java.util.BitSet()).set(column); }

    public static void clear() { BREACHES.clear(); POSTS.clear(); REBUILDS.clear(); SWEEPS.clear(); DEMOLITIONS.clear(); PREPAID.clear(); }

    // ---- 0.36: gates, corner towers and gatehouses -------------------------------------------------------

    public static final int TOWER_HEIGHT = 6;
    public static Map<StockResource, Integer> towerCost() { return Map.of(StockResource.STONE, 160, StockResource.WOOD, 32); }
    public static Map<StockResource, Integer> gatehouseCost(int gates) { return Map.of(StockResource.STONE, 48 * gates, StockResource.IRON, 4 * gates); }

    /** Why a design order cannot run now, or empty. */
    public static Optional<String> designBlocked(MinecraftServer server, UUID id, String order) {
        var wall = RpgSavedData.get(server).wall(id).orElse(null);
        if (wall == null || wall.tier() == 0) return Optional.of("wall.livingkingdoms.need_wall");
        // A design change while the old ring comes down would shift which old cells (gatehouses, towers) are demolished.
        if (!wall.complete() || REBUILDS.containsKey(id) || RpgSavedData.get(server).oldRadius(id).isPresent()) return Optional.of("wall.livingkingdoms.building");
        if (DefenseService.status(server, id) == SettlementSafety.THREATENED) return Optional.of("wall.livingkingdoms.threatened");
        var design = RpgSavedData.get(server).design(id);
        return switch (order) {
            case "towers" -> design.towers() ? Optional.of("wall.livingkingdoms.towers_done") : Optional.empty();
            case "gatehouses" -> design.gatehouses() ? Optional.of("wall.livingkingdoms.gatehouses_done")
                    : wall.tier() < 2 ? Optional.of("wall.livingkingdoms.need_stone") : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** Cycles the number of main gates (1–4) and rebuilds the ring's gates right away. Free. */
    public static boolean cycleGates(ServerPlayer player, UUID id) {
        var settlement = SettlementSavedData.get(player.server).get(id).orElse(null);
        if (settlement == null || !KingdomEconomy.authorized(player, id) || designBlocked(player.server, id, "gates").isPresent()) return false;
        var data = RpgSavedData.get(player.server);
        var previous = data.design(id);
        data.putDesign(id, previous.nextGates());
        rebuild(player.serverLevel(), id, previous);
        player.displayClientMessage(Component.translatable("wall.livingkingdoms.gates_set", data.design(id).gates()), true);
        return true;
    }

    public static boolean orderTowers(ServerPlayer player, UUID id) {
        if (designBlocked(player.server, id, "towers").isPresent() || unbuildable(player, id, true) || !pay(player, id, towerCost())) return false;
        var data = RpgSavedData.get(player.server);
        data.putDesign(id, data.design(id).withTowers());
        int built = towers(level(player.server, id), id);
        player.sendSystemMessage(Component.translatable("wall.livingkingdoms.towers_built", built).withStyle(ChatFormatting.GOLD));
        return true;
    }

    public static boolean orderGatehouses(ServerPlayer player, UUID id) {
        int gates = RpgSavedData.get(player.server).design(id).gates();
        if (designBlocked(player.server, id, "gatehouses").isPresent() || unbuildable(player, id, false) || !pay(player, id, gatehouseCost(gates))) return false;
        var data = RpgSavedData.get(player.server);
        data.putDesign(id, data.design(id).withGatehouses());
        gatehouses(level(player.server, id), id);
        player.sendSystemMessage(Component.translatable("wall.livingkingdoms.gatehouses_built", gates).withStyle(ChatFormatting.GOLD));
        return true;
    }

    /**
     * Refuses an order before it is paid when part of its footprint lies in an unloaded chunk, or when every tower
     * (every gatehouse flank) would fall inside a building, since the price could not be refunded cleanly.
     */
    private static boolean unbuildable(ServerPlayer player, UUID id, boolean towers) {
        var level = level(player.server, id);
        var settlement = SettlementSavedData.get(player.server).get(id).orElse(null);
        var wall = RpgSavedData.get(player.server).wall(id).orElse(null);
        if (level == null || settlement == null || wall == null) return true;
        var geo = WallGeometry.of(wall.radius(), RpgSavedData.get(player.server).design(id)); var ring = geo.ring(); var t = settlement.territory();
        boolean far = false, room = false;
        for (int c : towers ? geo.towerCenters() : geo.gateCenters()) for (int d = -WallGeometry.GATEHOUSE_REACH; d <= WallGeometry.GATEHOUSE_REACH; d++) {
            if (towers && d != 0) continue; // loaded() already covers a tower's 3×3 footprint
            int[] o = ring.get(Math.floorMod(c + d, ring.size()));
            var col = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            far |= !loaded(level, col);
            if (towers || Math.abs(d) >= 2) room |= !insideBuilding(level, settlement, col);
        }
        String reason = far ? "wall.livingkingdoms.far" : room ? null : "wall.livingkingdoms.no_room";
        if (reason != null) player.displayClientMessage(Component.translatable(reason), true);
        return reason != null;
    }

    private static boolean pay(ServerPlayer player, UUID id, Map<StockResource, Integer> cost) {
        if (!KingdomEconomy.authorized(player, id)) return false;
        var payment = KingdomEconomy.plan(player.server, id, cost, Map.of(), false).orElse(null);
        if (payment == null || !KingdomEconomy.valid(player.server, payment) || !KingdomEconomy.commit(player.server, payment)) {
            player.displayClientMessage(Component.translatable("wall.livingkingdoms.no_stock"), true); return false;
        }
        return true;
    }

    /** Re-applies the whole finished ring (gates opened or closed to match the design), then towers/gatehouses. */
    public static void rebuild(ServerLevel level, UUID id) { rebuild(level, id, null); }
    /**
     * Idempotent: re-applies every ring column on its real ground (removing anything of ours above the expected
     * height), demolishes the off-ring cells of {@code previous}'s towers (they slide with the gate count), then
     * re-raises this design's towers and gatehouses. {@code previous} is the design before this change, never persisted.
     */
    public static void rebuild(ServerLevel level, UUID id, WallDesign previous) {
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || settlement == null || wall.tier() == 0) return;
        var design = RpgSavedData.get(level.getServer()).design(id); var geo = WallGeometry.of(wall.radius(), design); var t = settlement.territory();
        if (previous != null && previous.towers()) {
            var old = WallGeometry.of(wall.radius(), previous);
            for (int c : old.towerCenters()) if (!geo.towerCenters().contains(c)) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int ox = old.ring().get(c)[0] + dx, oz = old.ring().get(c)[1] + dz;
                int x = t.x() + ox, z = t.z() + oz;
                if (geo.raised(ox, oz) || geo.onRing(ox, oz) || !level.hasChunkAt(new BlockPos(x, t.y(), z))) continue;
                int g = ground(level, x, z, true);
                strip(level, settlement, old, x, z, g, g);
            }
        }
        REBUILDS.put(id, 0);
        continueRebuild(level, id);
    }
    /** Re-applies up to {@link #REBUILD_COLUMNS} columns; the last batch re-raises towers and gatehouses. */
    private static void continueRebuild(ServerLevel level, UUID id) {
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        Integer from = REBUILDS.get(id);
        if (from == null || wall == null || settlement == null || wall.tier() == 0) { REBUILDS.remove(id); return; }
        var design = RpgSavedData.get(level.getServer()).design(id); var geo = WallGeometry.of(wall.radius(), design); var t = settlement.territory();
        int limit = wall.complete() ? geo.size() : Math.min(wall.built(), geo.size()), to = Math.min(limit, from + REBUILD_COLUMNS);
        for (int i = from; i < to; i++) {
            var o = geo.ring().get(i);
            if (loaded(level, new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]))) column(level, settlement, geo, wall.tier(), i);
        }
        if (to < limit) { REBUILDS.put(id, to); return; }
        REBUILDS.remove(id);
        if (design.towers()) towers(level, id);
        if (design.gatehouses()) gatehouses(level, id);
        POSTS.remove(id);
    }

    /** Four 3×3 towers near the diagonals (slid clear of every gate), six blocks tall; their tops are archer posts. */
    static int towers(ServerLevel level, UUID id) {
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || settlement == null) return 0;
        var geo = WallGeometry.of(wall.radius(), RpgSavedData.get(level.getServer()).design(id)); var t = settlement.territory(); int built = 0;
        for (int c : geo.towerCenters()) {
            var o = geo.ring().get(c);
            var center = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (!level.hasChunkAt(center) || insideBuilding(level, settlement, center)) continue;
            int floorY = ground(level, center.getX(), center.getZ(), true), mark = placed;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                var col = center.offset(dx, 0, dz);
                if (!level.hasChunkAt(col) || insideBuilding(level, settlement, col)) continue;
                int base = ground(level, col.getX(), col.getZ(), true);
                if (gate(level, settlement, new BlockPos(col.getX(), base - 1, col.getZ()))) continue;
                int y = Math.min(base, floorY + 2);
                for (int yy = y; yy < floorY + TOWER_HEIGHT; yy++) put(level, settlement, geo, new BlockPos(col.getX(), yy, col.getZ()), body(wall.tier()));
                if (dx != 0 && dz != 0) put(level, settlement, geo, new BlockPos(col.getX(), floorY + TOWER_HEIGHT, col.getZ()), cap(wall.tier()));
            }
            var lamp = new BlockPos(center.getX() + 1, floorY + TOWER_HEIGHT + 1, center.getZ() + 1);
            if (level.getBlockState(lamp).isAir() && wallBlock(level.getBlockState(lamp.below()))) level.setBlock(lamp, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
            if (placed > mark) level.sendParticles(ParticleTypes.CLOUD, center.getX() + .5, floorY + 3, center.getZ() + .5, 30, 1.2, 2, 1.2, 0);
            built++;
        }
        POSTS.remove(id);
        return built;
    }

    /** Raises flanking towers beside every main gate, a double lintel and kingdom banners facing outward. */
    static void gatehouses(ServerLevel level, UUID id) {
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || settlement == null) return;
        var geo = WallGeometry.of(wall.radius(), RpgSavedData.get(level.getServer()).design(id)); var ring = geo.ring(); var t = settlement.territory();
        for (int g : geo.gateCenters()) {
            var o = ring.get(g); var gateCol = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (!level.hasChunkAt(gateCol)) continue;
            int floorY = ground(level, gateCol.getX(), gateCol.getZ(), true);
            var outward = Direction.getNearest(o[0], 0, o[1]);
            for (int d = -1; d <= 1; d++) {
                var c = ring.get(Math.floorMod(g + d, ring.size()));
                var col = new BlockPos(t.x() + c[0], floorY + 3, t.z() + c[1]);
                put(level, settlement, geo, col, body(wall.tier())); put(level, settlement, geo, col.above(), body(wall.tier()));
            }
            for (int side : new int[]{-2, 2, -3, 3}) {
                var c = ring.get(Math.floorMod(g + side, ring.size()));
                var col = new BlockPos(t.x() + c[0], t.y(), t.z() + c[1]);
                if (!level.hasChunkAt(col) || insideBuilding(level, settlement, col)) continue;
                int y = ground(level, col.getX(), col.getZ(), true);
                for (int yy = y; yy < floorY + 6; yy++) put(level, settlement, geo, new BlockPos(col.getX(), yy, col.getZ()), body(wall.tier()));
                put(level, settlement, geo, new BlockPos(col.getX(), floorY + 6, col.getZ()), cap(wall.tier()));
                if (Math.abs(side) == 2) {
                    var face = new BlockPos(col.getX(), floorY + 4, col.getZ()).relative(outward);
                    if (level.getBlockState(face).isAir())
                        level.setBlock(face, Blocks.BLUE_WALL_BANNER.defaultBlockState().setValue(WallBannerBlock.FACING, outward), Block.UPDATE_ALL);
                }
            }
        }
        POSTS.remove(id);
    }

    /** Archer standing spots on the corner towers (when built). */
    static List<BlockPos> towerPosts(ServerLevel level, Settlement settlement, WallGeometry geo) {
        if (!geo.design().towers()) return List.of();
        var t = settlement.territory(); var out = new ArrayList<BlockPos>();
        for (int c : geo.towerCenters()) {
            var o = geo.ring().get(c);
            var col = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (!level.hasChunkAt(col)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ());
            var stand = new BlockPos(col.getX(), top, col.getZ());
            if (ours(settlement, geo, stand.below(), level.getBlockState(stand.below())) && level.getBlockState(stand).isAir()) out.add(stand);
        }
        return out;
    }
}

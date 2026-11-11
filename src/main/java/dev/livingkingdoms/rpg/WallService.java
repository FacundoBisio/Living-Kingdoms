package dev.livingkingdoms.rpg;

import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.SettlementSafety;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.GuardWork;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.WallDesign;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
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
 * pieces are repaired from storage. Only air, replaceable blocks, leaves and our own wall blocks are written.
 * Breach bookkeeping is transient (bounded per kingdom) and simply forgotten across restarts.
 */
public final class WallService {
    public static final int COLUMNS_PER_STEP = 1, STEP_TICKS = 5, SIEGE_TICKS = 40, REPAIR_TICKS = 100, MAX_BREACHES = 256;
    private static final Map<UUID, LinkedHashMap<BlockPos, BlockState>> BREACHES = new HashMap<>();
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

    /** Why a wall cannot be raised (or upgraded) right now, or empty when it can. */
    public static Optional<String> blocked(MinecraftServer server, Settlement settlement) {
        if (!settlement.faction().isAllied() || settlement.lifecycle() != SettlementLifecycle.ESTABLISHED) return Optional.of("wall.livingkingdoms.not_ready");
        var wall = RpgSavedData.get(server).wall(settlement.id()).orElse(null);
        if (wall != null && !wall.complete()) return Optional.of("wall.livingkingdoms.building");
        if (wall != null && wall.tier() >= Fortification.MAX_TIER) return Optional.of("wall.livingkingdoms.max");
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
        var next = new Fortification(current.tier(), radiusFor(settlement), 0, true).upgrade();
        if (!KingdomEconomy.commit(player.server, payment)) return false;
        data.putWall(settlementId, next);
        POSTS.remove(settlementId);
        player.sendSystemMessage(Component.translatable("wall.livingkingdoms.started",
                Component.translatable("wall.livingkingdoms.tier." + next.tier())).withStyle(ChatFormatting.GOLD));
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
        return placed;
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
    }

    private static ServerLevel level(MinecraftServer server, UUID id) {
        var settlement = SettlementSavedData.get(server).get(id).orElse(null);
        if (settlement == null || !settlement.faction().isAllied()) return null;
        var dimension = ResourceLocation.tryParse(settlement.territory().dimension());
        return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    /** Places the next column; false when it must wait (chunk unloaded) or nothing remains. */
    static boolean step(ServerLevel level, UUID id) {
        var data = RpgSavedData.get(level.getServer());
        var wall = data.wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || wall.complete() || settlement == null) return false;
        var ring = ring(wall.radius());
        if (wall.built() >= ring.size()) { finish(level, settlement, wall); return false; }
        var t = settlement.territory();
        int[] offset = ring.get(wall.built());
        var column = new BlockPos(t.x() + offset[0], t.y(), t.z() + offset[1]);
        if (!level.hasChunkAt(column)) return false;
        column(level, settlement, wall.tier(), wall.built(), offset, column, RpgSavedData.get(level.getServer()).design(id).gate(ring, wall.built()));
        var next = wall.progress(1);
        if (next.built() >= ring.size()) { data.putWall(id, next); finish(level, settlement, next); }
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
    private static boolean writable(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (level.getBlockEntity(pos) != null) return false;
        return state.isAir() || wallBlock(state) || state.is(BlockTags.LEAVES) || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    /** Lowest free position of a ring column, looking through any wall pieces already standing there. */
    private static int base(ServerLevel level, BlockPos column) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        for (int i = 0; i < 6; i++) {
            var below = level.getBlockState(new BlockPos(column.getX(), y - 1, column.getZ()));
            if (wallBlock(below) || below.getBlock() instanceof DoorBlock || below.is(BlockTags.LEAVES)) y--; else break;
        }
        return y;
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

    static void column(ServerLevel level, Settlement settlement, int tier, int index, int[] offset, BlockPos column) {
        column(level, settlement, tier, index, offset, column, false);
    }
    static void column(ServerLevel level, Settlement settlement, int tier, int index, int[] offset, BlockPos column, boolean mainGate) {
        if (insideBuilding(level, settlement, column)) return;
        int y = base(level, column);
        var floor = new BlockPos(column.getX(), y - 1, column.getZ());
        if (!level.getBlockState(floor).getFluidState().isEmpty()) return;
        var at = floor.above();
        if (mainGate) {
            // A main gate: a three-high opening under a lintel, wide enough for carts and horses.
            for (int dy = 0; dy <= 2; dy++) clear(level, at.above(dy));
            put(level, at.above(3), body(tier));
            return;
        }
        if (gate(level, settlement, floor)) {
            var facing = Direction.getNearest(offset[0], 0, offset[1]);
            if (!(level.getBlockState(at).getBlock() instanceof DoorBlock) && writable(level, at) && writable(level, at.above())) {
                var door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing);
                level.setBlock(at, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                level.setBlock(at.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            put(level, at.above(2), body(tier));
            return;
        }
        boolean post = index % Fortification.POST_SPACING == 0;
        put(level, at, body(tier));
        put(level, at.above(), body(tier));
        put(level, at.above(2), post ? body(tier) : cap(tier));
        if (post && level.getBlockState(at.above(3)).is(Blocks.SPRUCE_FENCE)) level.removeBlock(at.above(3), false);
        if (level.random.nextInt(3) == 0) level.sendParticles(ParticleTypes.CLOUD, at.getX() + .5, at.getY() + 1.5, at.getZ() + .5, 3, .3, .6, .3, 0);
        if (index % 4 == 0) level.playSound(null, at, tier == 1 ? SoundEvents.WOOD_PLACE : SoundEvents.STONE_PLACE, SoundSource.BLOCKS, .7F, 1F);
    }

    /** Removes our own wall pieces (never anything else) from a position. */
    private static void clear(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) { level.removeBlock(pos, false); return; }
        if (wallBlock(state)) level.removeBlock(pos, false);
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (!writable(level, pos)) return;
        if (level.getBlockState(pos).is(state.getBlock())) return;
        level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
    }

    /** Standing spots on top of the wall's archer posts (cached; recomputed when the wall changes). */
    public static List<BlockPos> posts(ServerLevel level, Settlement settlement) {
        var wall = RpgSavedData.get(level.getServer()).wall(settlement.id()).orElse(null);
        if (wall == null || wall.tier() == 0) return List.of();
        long now = level.getGameTime();
        var cached = POSTS.get(settlement.id());
        if (cached != null && cached.built == wall.built() && now - cached.at < 600) return cached.posts;
        var ring = ring(wall.radius());
        var t = settlement.territory();
        var posts = new ArrayList<BlockPos>();
        int limit = wall.complete() ? ring.size() : Math.min(ring.size(), wall.built());
        posts.addAll(towerPosts(level, settlement, wall));
        for (int i = 0; i < limit && posts.size() < 36; i += Fortification.POST_SPACING) {
            var column = new BlockPos(t.x() + ring.get(i)[0], t.y(), t.z() + ring.get(i)[1]);
            if (!level.hasChunkAt(column)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
            var stand = new BlockPos(column.getX(), top, column.getZ());
            var under = level.getBlockState(stand.below());
            if (wallBlock(under) && !under.is(Blocks.SPRUCE_FENCE) && !under.is(Blocks.COBBLESTONE_WALL) && !under.is(Blocks.STONE_BRICK_WALL)
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
        int checks = 0;
        for (var mob : attackers) {
            if (++checks > 24) break;
            for (var dir : Direction.Plane.HORIZONTAL) for (int dy = 0; dy <= 1; dy++) {
                var pos = mob.blockPosition().relative(dir).above(dy);
                var state = level.getBlockState(pos);
                if (!wallBlock(state) || level.random.nextFloat() >= .15F) continue;
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
        int repaired = 0;
        BlockPos last = null;
        var it = breaches.entrySet().iterator();
        while (it.hasNext() && repaired < limit) {
            var entry = it.next();
            var pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            if (!writable(level, pos) || wallBlock(level.getBlockState(pos))) { it.remove(); continue; }
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
        if (!wallBlock(state)) return;
        BREACHES.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(pos.immutable(), state);
        level.removeBlock(pos, false);
    }

    public static void clear() { BREACHES.clear(); POSTS.clear(); }

    // ---- 0.36: gates, corner towers and gatehouses -------------------------------------------------------

    public static final int TOWER_HEIGHT = 6;
    public static Map<StockResource, Integer> towerCost() { return Map.of(StockResource.STONE, 160, StockResource.WOOD, 32); }
    public static Map<StockResource, Integer> gatehouseCost(int gates) { return Map.of(StockResource.STONE, 48 * gates, StockResource.IRON, 4 * gates); }

    /** Why a design order cannot run now, or empty. */
    public static Optional<String> designBlocked(MinecraftServer server, UUID id, String order) {
        var wall = RpgSavedData.get(server).wall(id).orElse(null);
        if (wall == null || wall.tier() == 0) return Optional.of("wall.livingkingdoms.need_wall");
        if (!wall.complete()) return Optional.of("wall.livingkingdoms.building");
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
        data.putDesign(id, data.design(id).nextGates());
        rebuild(player.serverLevel(), id);
        player.displayClientMessage(Component.translatable("wall.livingkingdoms.gates_set", data.design(id).gates()), true);
        return true;
    }

    public static boolean orderTowers(ServerPlayer player, UUID id) {
        if (designBlocked(player.server, id, "towers").isPresent() || !pay(player, id, towerCost())) return false;
        var data = RpgSavedData.get(player.server);
        data.putDesign(id, data.design(id).withTowers());
        int placed = towers(player.serverLevel(), id);
        player.sendSystemMessage(Component.translatable("wall.livingkingdoms.towers_built", placed).withStyle(ChatFormatting.GOLD));
        return true;
    }

    public static boolean orderGatehouses(ServerPlayer player, UUID id) {
        int gates = RpgSavedData.get(player.server).design(id).gates();
        if (designBlocked(player.server, id, "gatehouses").isPresent() || !pay(player, id, gatehouseCost(gates))) return false;
        var data = RpgSavedData.get(player.server);
        data.putDesign(id, data.design(id).withGatehouses());
        gatehouses(player.serverLevel(), id);
        player.sendSystemMessage(Component.translatable("wall.livingkingdoms.gatehouses_built", gates).withStyle(ChatFormatting.GOLD));
        return true;
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
    public static void rebuild(ServerLevel level, UUID id) {
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || settlement == null || wall.tier() == 0) return;
        var ring = ring(wall.radius()); var design = RpgSavedData.get(level.getServer()).design(id); var t = settlement.territory();
        int limit = wall.complete() ? ring.size() : Math.min(wall.built(), ring.size());
        for (int i = 0; i < limit; i++) {
            var o = ring.get(i); var col = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (level.hasChunkAt(col)) column(level, settlement, wall.tier(), i, o, col, design.gate(ring, i));
        }
        if (design.towers()) towers(level, id);
        if (design.gatehouses()) gatehouses(level, id);
        POSTS.remove(id);
    }

    /** Four 3×3 towers on the diagonals, six blocks tall with crenellations; their tops are archer posts. */
    static int towers(ServerLevel level, UUID id) {
        var wall = RpgSavedData.get(level.getServer()).wall(id).orElse(null);
        var settlement = SettlementSavedData.get(level.getServer()).get(id).orElse(null);
        if (wall == null || settlement == null) return 0;
        var ring = ring(wall.radius()); var t = settlement.territory(); int built = 0;
        for (double angle : WallDesign.towerAngles()) {
            var o = ring.get(WallDesign.nearest(ring, angle));
            var center = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (!level.hasChunkAt(center) || insideBuilding(level, settlement, center)) continue;
            int floorY = base(level, center);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                var col = center.offset(dx, 0, dz);
                if (!level.hasChunkAt(col) || insideBuilding(level, settlement, col) || gate(level, settlement, new BlockPos(col.getX(), base(level, col) - 1, col.getZ()))) continue;
                int y = Math.min(base(level, col), floorY + 2);
                for (int yy = y; yy < floorY + TOWER_HEIGHT; yy++) put(level, new BlockPos(col.getX(), yy, col.getZ()), body(wall.tier()));
                if (dx != 0 && dz != 0) put(level, new BlockPos(col.getX(), floorY + TOWER_HEIGHT, col.getZ()), cap(wall.tier()));
            }
            var lamp = new BlockPos(center.getX() + 1, floorY + TOWER_HEIGHT + 1, center.getZ() + 1);
            if (level.getBlockState(lamp).isAir() && wallBlock(level.getBlockState(lamp.below()))) level.setBlock(lamp, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
            level.sendParticles(ParticleTypes.CLOUD, center.getX() + .5, floorY + 3, center.getZ() + .5, 30, 1.2, 2, 1.2, 0);
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
        var ring = ring(wall.radius()); var t = settlement.territory(); var design = RpgSavedData.get(level.getServer()).design(id);
        for (double angle : design.gateAngles()) {
            int g = WallDesign.nearest(ring, angle);
            var o = ring.get(g); var gateCol = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (!level.hasChunkAt(gateCol)) continue;
            int floorY = base(level, gateCol);
            var outward = Direction.getNearest(o[0], 0, o[1]);
            for (int d = -1; d <= 1; d++) {
                var c = ring.get(Math.floorMod(g + d, ring.size()));
                var col = new BlockPos(t.x() + c[0], floorY + 3, t.z() + c[1]);
                put(level, col, body(wall.tier())); put(level, col.above(), body(wall.tier()));
            }
            for (int side : new int[]{-2, 2, -3, 3}) {
                var c = ring.get(Math.floorMod(g + side, ring.size()));
                var col = new BlockPos(t.x() + c[0], t.y(), t.z() + c[1]);
                if (!level.hasChunkAt(col) || insideBuilding(level, settlement, col)) continue;
                int y = base(level, col);
                for (int yy = y; yy < floorY + 6; yy++) put(level, new BlockPos(col.getX(), yy, col.getZ()), body(wall.tier()));
                put(level, new BlockPos(col.getX(), floorY + 6, col.getZ()), cap(wall.tier()));
                if (Math.abs(side) == 2) {
                    var face = new BlockPos(col.getX(), floorY + 4, col.getZ()).relative(outward);
                    if (level.getBlockState(face).isAir())
                        level.setBlock(face, Blocks.BLUE_WALL_BANNER.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, outward), Block.UPDATE_ALL);
                }
            }
        }
        POSTS.remove(id);
    }

    /** Archer standing spots on the corner towers (when built). */
    static List<BlockPos> towerPosts(ServerLevel level, Settlement settlement, Fortification wall) {
        if (!RpgSavedData.get(level.getServer()).design(settlement.id()).towers()) return List.of();
        var ring = ring(wall.radius()); var t = settlement.territory(); var out = new ArrayList<BlockPos>();
        for (double angle : WallDesign.towerAngles()) {
            var o = ring.get(WallDesign.nearest(ring, angle));
            var col = new BlockPos(t.x() + o[0], t.y(), t.z() + o[1]);
            if (!level.hasChunkAt(col)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ());
            var stand = new BlockPos(col.getX(), top, col.getZ());
            if (wallBlock(level.getBlockState(stand.below())) && level.getBlockState(stand).isAir()) out.add(stand);
        }
        return out;
    }
}

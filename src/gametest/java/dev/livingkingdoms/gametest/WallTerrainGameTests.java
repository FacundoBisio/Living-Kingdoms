package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.rpg.WallService;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.WallDesign;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.38 hardening: walls raised over real terrain (plants, trees, water, slopes, neighbouring builds) instead of the
 * flat fixture, plus gate/tier/expansion cycles. Terrain is placed on the ring before the wall is built.
 */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WallTerrainGameTests {
    /** Positions of the terrain features, all at the natural ground level (first air layer of the fixture). */
    private record Terrain(List<BlockPos> plants, BlockPos trunk, BlockPos water, BlockPos house, BlockPos fence) {}

    private static Settlement establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        return SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow();
    }
    /** First air layer of the flat fixture built by {@code ConstructionGameTests.found}. */
    private static int ground(GameTestHelper h, int coordinate) { return h.absolutePos(new BlockPos(coordinate, 1, coordinate)).getY(); }
    private static BlockPos column(Settlement s, int[] o, int y) { return new BlockPos(s.territory().x() + o[0], y, s.territory().z() + o[1]); }
    private static BlockPos at(Settlement s, List<int[]> ring, double degrees, int y) { return column(s, ring.get(WallDesign.nearest(ring, Math.toRadians(degrees))), y); }

    /** Orders the next tier on {@code radius} the way the admin command does, and raises it at once. */
    private static Fortification raise(ServerLevel level, Settlement s, int radius) {
        var data = RpgSavedData.get(level.getServer());
        int tier = data.wall(s.id()).map(Fortification::tier).orElse(0);
        data.putWall(s.id(), new Fortification(tier, radius, 0, true).upgrade());
        WallService.buildAll(level, s.id());
        return data.wall(s.id()).orElseThrow();
    }
    private static void stock(ConstructionGameTests.Founded f, Settlement s, StockResource resource, int stacks) {
        var player = f.player();
        for (int i = 0; i < stacks; i++) { player.getInventory().setItem(0, new ItemStack(resource.item(), 64)); KingdomEconomy.deposit(player, s.id(), resource, 64); }
        player.getInventory().clearContent();
    }
    private static void cleanup(ServerLevel level, Settlement s) {
        var data = RpgSavedData.get(level.getServer());
        data.putWall(s.id(), Fortification.none(data.wall(s.id()).map(Fortification::radius).orElse(WallService.radiusFor(s))));
        WallService.clear();
    }
    /** Heights (relative to ground) of every wall block in a column. */
    private static List<Integer> wallHeights(ServerLevel level, BlockPos column, int ground) {
        var out = new ArrayList<Integer>();
        for (int y = ground - 3; y <= ground + 14; y++)
            if (WallService.wallBlock(level.getBlockState(new BlockPos(column.getX(), y, column.getZ())))) out.add(y - ground);
        return out;
    }
    /** Null when the column is a clean main gate: air at ground+0..2 and a single wall piece, the lintel at ground+3. */
    private static String gateProblem(ServerLevel level, BlockPos column, int ground) {
        var heights = wallHeights(level, column, ground);
        boolean open = true;
        for (int dy = 0; dy <= 2; dy++) open &= level.getBlockState(new BlockPos(column.getX(), ground + dy, column.getZ())).isAir();
        return open && heights.equals(List.of(3)) ? null : column.getX() + "," + column.getZ() + " open=" + open + " wall at ground+" + heights;
    }
    private static void assertMainGates(GameTestHelper h, Settlement s, int radius, int ground, String stage) {
        var level = h.getLevel(); var ring = WallService.ring(radius);
        var design = RpgSavedData.get(level.getServer()).design(s.id());
        for (int i = 0; i < ring.size(); i++) if (design.gate(ring, i)) {
            var problem = gateProblem(level, column(s, ring.get(i), ground), ground);
            h.assertTrue(problem == null, "Main-gate lintel drifts from ground+3 (base() reads the lintel as ground) " + stage + ": " + problem);
        }
    }

    /**
     * Places real terrain on ring columns away from every possible gate angle (0/90/180/270, 210/330) and tower
     * diagonal: two flowers and a sapling, a 5-high oak trunk with leaves, a 2-deep water pit, a rising 4-block slope,
     * and a stone-brick house wall plus a spruce-fence lamp post one block outside the ring.
     */
    private static Terrain terrain(GameTestHelper h, Settlement s, int radius, int ground) {
        var level = h.getLevel(); var ring = WallService.ring(radius);
        var ringCells = new HashSet<Long>();
        for (int[] o : ring) ringCells.add(BlockPos.asLong(s.territory().x() + o[0], ground, s.territory().z() + o[1]));
        var plants = List.of(at(s, ring, 15, ground), at(s, ring, 20, ground), at(s, ring, 25, ground));
        level.setBlock(plants.get(0), Blocks.POPPY.defaultBlockState(), 18);
        level.setBlock(plants.get(1), Blocks.DANDELION.defaultBlockState(), 18);
        level.setBlock(plants.get(2), Blocks.OAK_SAPLING.defaultBlockState(), 18);
        var trunk = at(s, ring, 65, ground);
        var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dy = 3; dy <= 5; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            level.setBlock(trunk.offset(dx, dy, dz), leaves, 18);
        for (int dy = 0; dy < 5; dy++) level.setBlock(trunk.above(dy), Blocks.OAK_LOG.defaultBlockState(), 18);
        var water = at(s, ring, 110, ground);
        level.setBlock(water.below(), Blocks.WATER.defaultBlockState(), 18);
        level.setBlock(water.below(2), Blocks.WATER.defaultBlockState(), 18);
        int slope = WallDesign.nearest(ring, Math.toRadians(150));
        for (int k = 0; k < 8; k++) {
            var col = column(s, ring.get((slope + k) % ring.size()), ground);
            int rise = 1 + k / 2;
            for (int dy = 0; dy < rise - 1; dy++) level.setBlock(col.above(dy), Blocks.DIRT.defaultBlockState(), 18);
            level.setBlock(col.above(rise - 1), Blocks.GRASS_BLOCK.defaultBlockState(), 18);
        }
        var house = outside(at(s, ring, 250, ground), s, ringCells);
        level.setBlock(house, Blocks.STONE_BRICKS.defaultBlockState(), 18);
        level.setBlock(house.above(), Blocks.STONE_BRICKS.defaultBlockState(), 18);
        var fence = outside(at(s, ring, 240, ground), s, ringCells);
        level.setBlock(fence, Blocks.SPRUCE_FENCE.defaultBlockState(), 18);
        level.setBlock(fence.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 18);
        level.setBlock(fence.above(2), Blocks.LANTERN.defaultBlockState(), 18);
        return new Terrain(plants, trunk, water, house, fence);
    }
    /** The first cell radially outward from a ring column that is not itself a ring column. */
    private static BlockPos outside(BlockPos ringColumn, Settlement s, Set<Long> ringCells) {
        double a = Math.atan2(ringColumn.getZ() - s.territory().z(), ringColumn.getX() - s.territory().x());
        for (int d = 1; d <= 3; d++) {
            var p = new BlockPos(ringColumn.getX() + (int) Math.round(d * Math.cos(a)), ringColumn.getY(), ringColumn.getZ() + (int) Math.round(d * Math.sin(a)));
            if (!ringCells.contains(p.asLong())) return p;
        }
        throw new IllegalStateException("No free cell outside the ring at " + ringColumn);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void lintelStaysAtGroundPlusThreeAcrossTiersAndGateCycles(GameTestHelper h) {
        int coordinate = 1100000;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        terrain(h, s, radius, ground);
        for (int tier = 1; tier <= Fortification.MAX_TIER; tier++) {
            var wall = raise(level, s, radius);
            h.assertTrue(wall.complete() && wall.tier() == tier, "Tier " + tier + " stands: " + wall);
            assertMainGates(h, s, radius, ground, "after tier " + tier);
        }
        int gates = RpgSavedData.get(level.getServer()).design(s.id()).gates();
        for (int i = 0; i < WallDesign.MAX_GATES; i++) h.assertTrue(WallService.cycleGates(f.player(), s.id()), "Gate cycle " + i + " accepted");
        h.assertTrue(RpgSavedData.get(level.getServer()).design(s.id()).gates() == gates, "Four cycles return to the same gate count");
        assertMainGates(h, s, radius, ground, "after four gate cycles");
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void loweringGateCountClosesOldOpening(GameTestHelper h) {
        int coordinate = 1101024;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var data = RpgSavedData.get(level.getServer()); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        terrain(h, s, radius, ground);
        raise(level, s, radius);
        while (data.design(s.id()).gates() != WallDesign.MAX_GATES) h.assertTrue(WallService.cycleGates(f.player(), s.id()), "Gates cycle up to four");
        var ring = WallService.ring(radius);
        var four = data.design(s.id());
        h.assertTrue(WallService.cycleGates(f.player(), s.id()) && data.design(s.id()).gates() == 1, "Gates cycle down to one");
        var one = data.design(s.id());
        int closed = 0;
        for (int i = 0; i < ring.size(); i++) if (four.gate(ring, i) && !one.gate(ring, i)) {
            var col = column(s, ring.get(i), ground);
            var heights = wallHeights(level, col, ground);
            boolean solid = heights.contains(0) && heights.contains(1) && heights.contains(2);
            boolean floating = heights.stream().anyMatch(dy -> dy > 3);
            h.assertTrue(solid && !floating, "Old gate opening not closed after lowering gate count at " + col.getX() + "," + col.getZ() + ": wall at ground+" + heights);
            closed++;
        }
        h.assertTrue(closed > 0, "Some former gate columns were checked");
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void noGapUnderWallOverFlowersAndSaplings(GameTestHelper h) {
        int coordinate = 1102048;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        var terrain = terrain(h, s, radius, ground);
        raise(level, s, radius);
        for (var plant : terrain.plants()) {
            var state = level.getBlockState(plant);
            h.assertTrue(state.is(WallService.body(1).getBlock()), "Wall floats over a plant (writable() rejects flowers/saplings) at "
                    + plant.getX() + "," + plant.getZ() + ": ground block is " + state + ", wall at ground+" + wallHeights(level, plant, ground));
        }
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void wallIsNotBuiltOnTreeTrunks(GameTestHelper h) {
        int coordinate = 1103072;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        var trunk = terrain(h, s, radius, ground).trunk();
        raise(level, s, radius);
        var heights = wallHeights(level, trunk, ground);
        h.assertTrue(level.getBlockState(trunk).is(WallService.body(1).getBlock()) && heights.stream().allMatch(dy -> dy < 5),
                "Wall built on top of a tree trunk (logs count for MOTION_BLOCKING_NO_LEAVES): ground block is " + level.getBlockState(trunk) + ", wall at ground+" + heights);
        cleanup(level, s);
        h.succeed();
    }

    /**
     * siege() is package-private and only runs on the tick while the kingdom is THREATENED, with a 15% roll per piece,
     * so it is not reachable deterministically from a test. breach() is the public "as if an invader broke it" path and
     * uses the same material-only ownership rule (wallBlock), so it reproduces the bug deterministically.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void siegeNeverBreaksNeighbouringHouseOrLampPost(GameTestHelper h) {
        int coordinate = 1104096;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        var terrain = terrain(h, s, radius, ground);
        raise(level, s, radius);
        h.assertTrue(level.getBlockState(terrain.house()).is(Blocks.STONE_BRICKS) && level.getBlockState(terrain.fence()).is(Blocks.SPRUCE_FENCE),
                "Wall building left the neighbouring house and lamp post alone");
        WallService.breach(level, s.id(), terrain.house());
        WallService.breach(level, s.id(), terrain.fence());
        h.assertTrue(level.getBlockState(terrain.house()).is(Blocks.STONE_BRICKS) && level.getBlockState(terrain.fence()).is(Blocks.SPRUCE_FENCE),
                "Invaders break player blocks outside the ring (wallBlock() decides ownership by material): house "
                        + level.getBlockState(terrain.house()) + ", lamp post " + level.getBlockState(terrain.fence()) + ", breaches " + WallService.breaches(s.id()));
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void towersNeverSealAMainGate(GameTestHelper h) {
        int coordinate = 1105120;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var data = RpgSavedData.get(level.getServer()); int ground = ground(h, coordinate);
        var layout = SettlementSavedData.get(level.getServer()).layout(s.id()).orElse(null);
        java.util.function.Predicate<BlockPos> inBuilding = col -> layout != null && layout.buildings().stream().anyMatch(b -> col.getX() >= b.bounds().minX() - 1
                && col.getX() <= b.bounds().maxX() + 1 && col.getZ() >= b.bounds().minZ() - 1 && col.getZ() <= b.bounds().maxZ() + 1);
        // Small walls put the tower diagonals a few blocks from the 3-gate angles. Pick the smallest radius (from the
        // radiusFor clamp up) where a tower footprint touches a main-gate column that the camp's buildings do not cover.
        var three = new WallDesign(3, false, false);
        int radius = -1; int overlaps = 0;
        for (int r = 8; r <= 16 && radius < 0; r++) {
            var ring = WallService.ring(r);
            for (int i = 0; i < ring.size(); i++) if (three.gate(ring, i)) for (double angle : WallDesign.towerAngles()) {
                var tower = ring.get(WallDesign.nearest(ring, angle)); var o = ring.get(i);
                var col = column(s, o, ground); var centre = column(s, tower, ground);
                if (Math.abs(o[0] - tower[0]) <= 1 && Math.abs(o[1] - tower[1]) <= 1 && !inBuilding.test(col) && !inBuilding.test(centre)) { radius = r; overlaps++; }
            }
        }
        // No overlap with the current gate angles: still check a small wall, which then must keep every gate open.
        if (radius < 0) radius = 12;
        raise(level, s, radius);
        while (data.design(s.id()).gates() != 3) h.assertTrue(WallService.cycleGates(f.player(), s.id()), "Gates cycle to three");
        stock(f, s, StockResource.STONE, 3); stock(f, s, StockResource.WOOD, 1);
        h.assertTrue(WallService.orderTowers(f.player(), s.id()), "Towers ordered and paid");
        var ring = WallService.ring(radius); var design = data.design(s.id());
        int checked = 0;
        for (int i = 0; i < ring.size(); i++) if (design.gate(ring, i)) {
            var col = column(s, ring.get(i), ground);
            if (inBuilding.test(col)) continue;
            boolean open = true;
            for (int dy = 0; dy <= 2; dy++) open &= level.getBlockState(col.above(dy)).isAir();
            h.assertTrue(open, "A corner tower seals a main gate (wall radius " + radius + ", " + overlaps + " overlapping columns) at " + col.getX() + "," + col.getZ() + ": wall at ground+" + wallHeights(level, col, ground));
            checked++;
        }
        h.assertTrue(checked > 0, "Some main-gate columns lie outside building footprints (radius " + radius + ")");
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void expandingTerritoryDoesNotLeaveTwoRings(GameTestHelper h) {
        int coordinate = 1106144;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var settlements = SettlementSavedData.get(level.getServer()); int ground = ground(h, coordinate);
        int oldRadius = WallService.radiusFor(s);
        terrain(h, s, oldRadius, ground);
        raise(level, s, oldRadius);
        h.assertTrue(settlements.expand(s, s.territory().radius() + 16), "Territory expands");
        var grown = settlements.get(s.id()).orElseThrow();
        int newRadius = WallService.radiusFor(grown);
        h.assertTrue(newRadius != oldRadius, "The wall radius follows the territory: " + oldRadius + " -> " + newRadius);
        var wall = raise(level, grown, newRadius);
        h.assertTrue(wall.complete() && wall.tier() == 2, "Tier 2 stands on the new ring: " + wall);
        var newCells = new HashSet<Long>();
        for (int[] o : WallService.ring(newRadius)) newCells.add(BlockPos.asLong(o[0], 0, o[1]));
        int leftover = 0; BlockPos sample = null;
        for (int[] o : WallService.ring(oldRadius)) if (!newCells.contains(BlockPos.asLong(o[0], 0, o[1]))) {
            var col = column(grown, o, ground);
            if (!wallHeights(level, col, ground).isEmpty()) { leftover++; if (sample == null) sample = col; }
        }
        h.assertTrue(leftover == 0, "Expanding the territory leaves the old ring standing: " + leftover + " old columns still carry wall, e.g. " + sample);
        cleanup(level, grown);
        h.succeed();
    }

    /** Any piece a wall places: wall blocks, gate doors, tower lanterns, gatehouse banners. */
    private static boolean piece(net.minecraft.world.level.block.state.BlockState state) {
        return WallService.wallBlock(state) || state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || state.is(Blocks.LANTERN) || state.is(Blocks.BLUE_WALL_BANNER);
    }
    private static void fund(ConstructionGameTests.Founded f, Settlement s, StockResource resource, int amount) {
        for (int i = 0; i < 8 && KingdomEconomy.count(f.player().server, s.id(), resource) < amount; i++) stock(f, s, resource, 1);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void movingATierThreeWallAfterExpansion(GameTestHelper h) {
        int coordinate = 1114336;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var server = level.getServer(); var data = RpgSavedData.get(server); int ground = ground(h, coordinate);
        int oldRadius = WallService.radiusFor(s);
        for (int i = 0; i < Fortification.MAX_TIER; i++) raise(level, s, oldRadius);
        fund(f, s, StockResource.STONE, WallService.towerCost().get(StockResource.STONE)); fund(f, s, StockResource.WOOD, WallService.towerCost().get(StockResource.WOOD));
        h.assertTrue(WallService.orderTowers(f.player(), s.id()), "Towers ordered on the old ring");
        h.assertTrue(SettlementSavedData.get(server).expand(s, s.territory().radius() + 16), "Territory expands");
        var grown = SettlementSavedData.get(server).get(s.id()).orElseThrow();
        int newRadius = WallService.radiusFor(grown);
        h.assertTrue(WallService.blocked(server, grown).isEmpty(), "A tier-3 wall off the border offers a move, not 'max': " + WallService.blocked(server, grown));
        int[] cost = Fortification.cost(3, newRadius), old = Fortification.cost(3, oldRadius);
        fund(f, grown, StockResource.WOOD, cost[0]); fund(f, grown, StockResource.STONE, cost[1]); fund(f, grown, StockResource.IRON, cost[2]);
        int stone = stone(level, grown);
        h.assertTrue(WallService.fortify(f.player(), grown.id()), "Move order accepted: " + WallService.blocked(server, grown) + " cost " + WallService.nextCost(server, grown));
        h.assertTrue(stone(level, grown) == stone - cost[1] + old[1] / 2, "Move charged " + cost[1] + " Stone and refunded " + old[1] / 2 + ": " + stone + " -> " + stone(level, grown));
        var moving = data.wall(grown.id()).orElseThrow();
        h.assertTrue(moving.tier() == 3 && moving.radius() == newRadius && !moving.complete() && data.oldRadius(grown.id()).isPresent(), "Same tier, new radius, old ring pending: " + moving);
        h.assertTrue(!WallService.fortify(f.player(), grown.id()), "No second order while the wall moves");
        WallService.buildAll(level, grown.id());
        h.assertTrue(data.wall(grown.id()).orElseThrow().complete() && data.oldRadius(grown.id()).isEmpty(), "Move finished and old ring demolished");
        var design = data.design(grown.id()); var oldGeo = dev.livingkingdoms.rpg.domain.WallGeometry.of(oldRadius, design); var newGeo = dev.livingkingdoms.rpg.domain.WallGeometry.of(newRadius, design);
        int leftover = 0; BlockPos sample = null;
        for (int dx = -oldRadius - 2; dx <= oldRadius + 2; dx++) for (int dz = -oldRadius - 2; dz <= oldRadius + 2; dz++) {
            if (!oldGeo.ownsAnyDesign(dx, dz) || newGeo.ownsAnyDesign(dx, dz)) continue;
            var col = column(grown, new int[]{dx, dz}, ground);
            for (int y = ground - 3; y <= ground + 14; y++) if (piece(level.getBlockState(col.atY(y)))) { leftover++; if (sample == null) sample = col.atY(y); }
        }
        h.assertTrue(leftover == 0, "Old ring pieces survive the move: " + leftover + ", e.g. " + sample + " " + (sample == null ? "" : level.getBlockState(sample)));
        int towers = 0;
        for (int c : newGeo.towerCenters()) if (wallHeights(level, column(grown, newGeo.ring().get(c), ground), ground).contains(WallService.TOWER_HEIGHT - 1)) towers++;
        h.assertTrue(towers > 0, "Towers stand on the new ring");
        cleanup(level, grown);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void houseBlocksNearOldRingSurviveDemolition(GameTestHelper h) {
        int coordinate = 1115360;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var settlements = SettlementSavedData.get(level.getServer()); int ground = ground(h, coordinate);
        int oldRadius = WallService.radiusFor(s);
        raise(level, s, oldRadius);
        var geo = dev.livingkingdoms.rpg.domain.WallGeometry.of(oldRadius, RpgSavedData.get(level.getServer()).design(s.id()));
        var ring = WallService.ring(oldRadius); var ringColumn = at(s, ring, 250, ground);
        double a = Math.atan2(ringColumn.getZ() - s.territory().z(), ringColumn.getX() - s.territory().x());
        BlockPos outside = null, inside = null;
        for (int d = 1; d <= 3; d++) for (int sign : new int[]{1, -1}) {
            var p = new BlockPos(ringColumn.getX() + (int) Math.round(sign * d * Math.cos(a)), ground, ringColumn.getZ() + (int) Math.round(sign * d * Math.sin(a)));
            if (geo.ownsAnyDesign(p.getX() - s.territory().x(), p.getZ() - s.territory().z())) continue;
            if (sign > 0 && outside == null) outside = p; else if (sign < 0 && inside == null) inside = p;
        }
        h.assertTrue(outside != null && inside != null, "Free cells beside the old ring");
        for (var p : List.of(outside, inside)) { level.setBlock(p, Blocks.STONE_BRICKS.defaultBlockState(), 18); level.setBlock(p.above(), Blocks.STONE_BRICKS.defaultBlockState(), 18); }
        h.assertTrue(settlements.expand(s, s.territory().radius() + 16), "Territory expands");
        var grown = settlements.get(s.id()).orElseThrow();
        raise(level, grown, WallService.radiusFor(grown));
        h.assertTrue(RpgSavedData.get(level.getServer()).oldRadius(s.id()).isEmpty(), "Old ring demolished");
        for (var p : List.of(outside, inside)) h.assertTrue(level.getBlockState(p).is(Blocks.STONE_BRICKS) && level.getBlockState(p.above()).is(Blocks.STONE_BRICKS),
                "Demolition took a house block beside the old ring at " + p + ": " + level.getBlockState(p));
        cleanup(level, grown);
        h.succeed();
    }

    /** Every block of the ring footprint (each ring cell and its 8 neighbours), ground-2..ground+12. */
    private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snapshot(ServerLevel level, Settlement s, int radius, int ground) {
        var out = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int[] o : WallService.ring(radius)) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = ground - 2; y <= ground + 12; y++) {
            var p = new BlockPos(s.territory().x() + o[0] + dx, y, s.territory().z() + o[1] + dz);
            out.putIfAbsent(p, level.getBlockState(p));
        }
        return out;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void rebuildIsIdempotent(GameTestHelper h) {
        int coordinate = 1108192;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var data = RpgSavedData.get(level.getServer()); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        terrain(h, s, radius, ground);
        raise(level, s, radius); raise(level, s, radius);
        stock(f, s, StockResource.STONE, 5); stock(f, s, StockResource.WOOD, 1); stock(f, s, StockResource.IRON, 1);
        h.assertTrue(WallService.orderTowers(f.player(), s.id()) && WallService.orderGatehouses(f.player(), s.id()), "Towers and gatehouses ordered");
        var before = snapshot(level, s, radius, ground);
        int gates = data.design(s.id()).gates();
        for (int i = 0; i < WallDesign.MAX_GATES; i++) h.assertTrue(WallService.cycleGates(f.player(), s.id()), "Gate cycle " + i + " accepted");
        h.assertTrue(data.design(s.id()).gates() == gates, "Four cycles return to the same gate count");
        var after = snapshot(level, s, radius, ground);
        var diff = new ArrayList<String>();
        for (var e : before.entrySet()) if (!e.getValue().equals(after.get(e.getKey())))
            diff.add(e.getKey().getX() + "," + (e.getKey().getY() - ground) + "," + e.getKey().getZ() + ": " + e.getValue() + " -> " + after.get(e.getKey()));
        h.assertTrue(diff.isEmpty(), "Rebuild is not idempotent, " + diff.size() + " blocks changed, e.g. " + diff.subList(0, Math.min(4, diff.size())));
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void slopeColumnsHaveNoGapUnderWall(GameTestHelper h) {
        int coordinate = 1109216;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        terrain(h, s, radius, ground);
        raise(level, s, radius);
        var ring = WallService.ring(radius); int slope = WallDesign.nearest(ring, Math.toRadians(150));
        for (int k = 0; k < 8; k++) {
            var col = column(s, ring.get((slope + k) % ring.size()), ground);
            int own = ground + 1 + k / 2;
            var heights = wallHeights(level, col, own);
            boolean contiguous = !heights.isEmpty();
            for (int i = 0; i < heights.size(); i++) contiguous &= heights.get(i) == i;
            h.assertTrue(contiguous && heights.size() >= 3, "Air gap or missing wall on a slope column " + col.getX() + "," + col.getZ() + ": wall at own ground+" + heights);
        }
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void waterColumnsAreNotLeftOpen(GameTestHelper h) {
        int coordinate = 1107168;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        var water = terrain(h, s, radius, ground).water();
        raise(level, s, radius);
        var heights = wallHeights(level, water, ground);
        h.assertTrue(heights.contains(-1) || heights.contains(0), "Wall skips a water column and leaves a hole at "
                + water.getX() + "," + water.getZ() + ": wall at ground+" + heights + " (water surface is ground-1)");
        cleanup(level, s);
        h.succeed();
    }

    private static int stone(ServerLevel level, Settlement s) { return KingdomEconomy.count(level.getServer(), s.id(), StockResource.STONE); }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void repairSweepRestoresBrokenPiecesAfterRestart(GameTestHelper h) {
        int coordinate = 1110240;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        raise(level, s, radius); raise(level, s, radius);
        stock(f, s, StockResource.STONE, 1);
        var ring = WallService.ring(radius); var broken = new ArrayList<BlockPos>();
        for (double degrees : new double[]{20, 40, 60}) {
            var pos = at(s, ring, degrees, ground + 1);
            h.assertTrue(level.getBlockState(pos).is(WallService.body(2).getBlock()), "Tier 2 body stands at " + pos + ": " + level.getBlockState(pos));
            level.removeBlock(pos, false); broken.add(pos);
        }
        WallService.clear(); // a restart forgets every transient breach
        int before = stone(level, s);
        WallService.sweep(level, s.id(), ring.size());
        for (var pos : broken) h.assertTrue(level.getBlockState(pos).is(WallService.body(2).getBlock()),
                "Repair sweep left a broken piece after a restart (breaches lived only in memory) at " + pos + ": " + level.getBlockState(pos));
        h.assertTrue(stone(level, s) == before - 3, "Three restored pieces cost three Stone: " + before + " -> " + stone(level, s));
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void repairSweepDoesNotChargeColumnsTheTierAlreadyPaid(GameTestHelper h) {
        int coordinate = 1116384;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        raise(level, s, radius); raise(level, s, radius);
        stock(f, s, StockResource.STONE, 1);
        var ring = WallService.ring(radius); var pos = at(s, ring, 40, ground + 1);
        level.removeBlock(pos, false); // stands in for a column an unloaded chunk skipped during the build
        for (int i = 0; i < ring.size(); i++) WallService.prepaid(s.id(), i);
        int before = stone(level, s);
        WallService.sweep(level, s.id(), ring.size());
        h.assertTrue(level.getBlockState(pos).is(WallService.body(2).getBlock()), "Sweep raised the skipped column at " + pos);
        h.assertTrue(stone(level, s) == before, "A column the tier price covered was charged again: " + before + " -> " + stone(level, s));
        level.removeBlock(pos, false);
        WallService.sweep(level, s.id(), ring.size());
        h.assertTrue(stone(level, s) == before - 1, "A later real breach in that column costs Stone: " + before + " -> " + stone(level, s));
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void repairSweepIsFreeWhenWallIsIntact(GameTestHelper h) {
        int coordinate = 1111264;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); int ground = ground(h, coordinate); int radius = WallService.radiusFor(s);
        terrain(h, s, radius, ground);
        raise(level, s, radius); raise(level, s, radius);
        stock(f, s, StockResource.STONE, 1);
        var before = snapshot(level, s, radius, ground); int stone = stone(level, s);
        int placed = WallService.sweep(level, s.id(), WallService.ring(radius).size());
        var after = snapshot(level, s, radius, ground); int changed = 0;
        for (var e : before.entrySet()) if (!e.getValue().equals(after.get(e.getKey()))) changed++;
        h.assertTrue(placed == 0 && changed == 0 && stone(level, s) == stone,
                "A sweep over an intact wall did work: placed " + placed + ", changed " + changed + ", stone " + stone + " -> " + stone(level, s));
        cleanup(level, s);
        h.succeed();
    }

    /** Fixture chunks reach ~96 blocks from the centre, so a radius-256 ring crosses chunks nobody has loaded. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void ringCompletesWhenAColumnCannotBeBuiltNow(GameTestHelper h) {
        int coordinate = 1112288;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var data = RpgSavedData.get(level.getServer()); int radius = 256;
        long unloaded = WallService.ring(radius).stream().filter(o -> !level.hasChunkAt(column(s, o, s.territory().y()))).count();
        h.assertTrue(unloaded > 0, "Some radius-" + radius + " ring column lies in an unloaded chunk");
        data.putWall(s.id(), Fortification.none(radius).upgrade());
        WallService.buildAll(level, s.id());
        var wall = data.wall(s.id()).orElseThrow();
        h.assertTrue(wall.complete(), "The ring stays 'building' forever behind an unloaded chunk (" + unloaded + " unloaded columns): " + wall);
        cleanup(level, s);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void towersAreNotChargedWhenTheyCannotBeBuilt(GameTestHelper h) {
        int coordinate = 1113312;
        var f = ConstructionGameTests.found(h, coordinate); var s = establish(h, f);
        var level = h.getLevel(); var data = RpgSavedData.get(level.getServer()); int radius = 256;
        data.putWall(s.id(), new Fortification(1, radius, 0, true));
        var ring = WallService.ring(radius);
        h.assertTrue(data.design(s.id()).towers() == false && !level.hasChunkAt(at(s, ring, 45, s.territory().y())), "A tower diagonal lies in an unloaded chunk");
        stock(f, s, StockResource.STONE, 3); stock(f, s, StockResource.WOOD, 1);
        int stone = stone(level, s), wood = KingdomEconomy.count(level.getServer(), s.id(), StockResource.WOOD);
        h.assertTrue(!WallService.orderTowers(f.player(), s.id()), "Towers that cannot be built are refused");
        h.assertTrue(stone(level, s) == stone && KingdomEconomy.count(level.getServer(), s.id(), StockResource.WOOD) == wood && !data.design(s.id()).towers(),
                "Towers charged or marked built although nothing could be built: stone " + stone + " -> " + stone(level, s) + ", towers " + data.design(s.id()).towers());
        cleanup(level, s);
        h.succeed();
    }
}

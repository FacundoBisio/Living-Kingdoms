package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.rpg.WallFootprint;
import dev.livingkingdoms.rpg.WallService;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.WallGeometry;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 0.38: the plot planner and Builders respect the kingdom wall (footprint, margin and gate corridors). */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WallConstructionGameTests {
    private static Settlement establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        return SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void plannedPlotsNeverOverlapTheWall(GameTestHelper h) {
        var level = h.getLevel(); var server = level.getServer();
        var s = establish(h, ConstructionGameTests.found(h, 1120000));
        // A ring well inside the default territory, so the planner's 13..30 block search crosses it.
        int radius = 20;
        RpgSavedData.get(server).putWall(s.id(), new Fortification(0, radius, 0, true).upgrade());
        WallService.buildAll(level, s.id());
        var geo = WallGeometry.of(radius, RpgSavedData.get(server).design(s.id()));
        int cx = s.territory().x(), cz = s.territory().z();
        for (int g : geo.gateCenters()) {
            int[] o = geo.ring().get(g); int sx = Integer.signum(o[0]), sz = Integer.signum(o[1]);
            h.assertTrue(WallFootprint.reserved(server, s, cx + o[0] - sx * 4, cz + o[1] - sz * 4)
                    && WallFootprint.reserved(server, s, cx + o[0] + sx * 4, cz + o[1] + sz * 4), "Gate corridor is reserved 4 deep on both sides");
            h.assertTrue(!WallFootprint.reserved(server, s, cx + o[0] - sx * 7, cz + o[1] - sz * 7), "The corridor stops after 4 blocks");
        }
        var metadata = SettlementSavedData.get(server).layout(s.id()).orElseThrow();
        var catalog = BuildingCatalog.load(level, metadata.style());
        var occupied = new ArrayList<>(metadata.buildings().stream().map(SettlementLayoutMetadata.Building::bounds).toList());
        occupied.addAll(ConstructionSavedData.get(server).reservations(s.id()));
        Map<BlockPos, BlockState> paths = new LinkedHashMap<>();
        metadata.paths().forEach(pos -> paths.put(pos, level.getBlockState(pos)));
        int planned = 0;
        for (int i = 0; i < 12; i++) {
            var plan = new SettlementLayoutPlanner().planAddition(level, s.territory(), catalog, BuildingKind.HOUSE, occupied,
                    metadata.ports(), paths, new GenerationDiagnostics());
            if (plan.isEmpty()) break;
            var bounds = plan.get().buildings().getFirst().bounds();
            h.assertTrue(!WallFootprint.reserved(server, s, bounds), "Plot " + i + " " + bounds + " keeps off the wall, its margin and gate corridors");
            occupied.add(bounds); paths.putAll(plan.get().pathBlocks()); planned++;
        }
        h.assertTrue(planned > 0, "A walled kingdom still finds room inside the ring");
        RpgSavedData.get(server).putWall(s.id(), Fortification.none(radius));
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void buildersNeverClearWallBlocks(GameTestHelper h) {
        var level = h.getLevel(); var server = level.getServer();
        var f = ConstructionGameTests.found(h, 1121024); var s = f.settlement();
        var entry = ConstructionService.current(server, s.id()).orElseThrow();
        supply(f, entry.project().required());
        h.assertTrue(ConstructionService.deposit(f.player(), s.id(), entry.project().id()), "Founding project is funded");
        entry = ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow();
        h.assertTrue(entry.project().state() == ConstructionState.BUILDING && !entry.project().builderRequired(), "Timed project is building");
        // Pick a ring that crosses the site interior (an old wall the plot was planned over before 0.38).
        var bounds = entry.plan().buildings().getFirst().bounds(); var origin = entry.plan().buildings().getFirst().origin();
        int cx = s.territory().x(), cz = s.territory().z(); BlockPos target = null; int radius = 0;
        search:
        for (int x = bounds.minX() + 1; x < bounds.maxX(); x++) for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
            int r = (int) Math.round(Math.hypot(x - cx, z - cz));
            if (r >= 8 && WallGeometry.of(r, RpgSavedData.get(server).design(s.id())).onRing(x - cx, z - cz)) { target = new BlockPos(x, origin.getY() + 1, z); radius = r; break search; }
        }
        h.assertTrue(target != null, "Some interior cell of the site lies on a ring");
        RpgSavedData.get(server).putWall(s.id(), new Fortification(1, radius, 0, true));
        level.setBlock(target, WallService.body(1), 18);
        var id = entry.project().id();
        h.assertTrue(!ConstructionService.resolve(server, id, f.player(), true) && ConstructionService.obstacles(id).contains(target), "The wall block is reported as an obstacle");
        for (int i = 0; i < 4; i++) ConstructionService.advanceTimed(server, id);
        h.assertTrue(level.getBlockState(target).equals(WallService.body(1)), "Builders never remove a wall block");
        var reason = ConstructionService.reason(id).orElseThrow();
        h.assertTrue(reason.key().equals("construction.livingkingdoms.reason.wall"), "The stall shows the wall reason, got " + reason.key());
        RpgSavedData.get(server).putWall(s.id(), Fortification.none(radius));
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void wallsTabCarriesRingProgress(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var f = ConstructionGameTests.found(h, 1117408); var s = f.settlement();
        var data = RpgSavedData.get(server); var design = data.design(s.id());
        var none = dev.livingkingdoms.ui.ManagementService.open(f.player(), s.id()) ? dev.livingkingdoms.ui.ManagementService.snapshot(f.player()).orElseThrow().getCompound("wall") : null;
        h.assertTrue(none != null && none.getInt("built") == 0 && none.getInt("radius") == WallService.radiusFor(s) && !none.contains("ringRadius")
                && none.getInt("total") == WallGeometry.of(WallService.radiusFor(s), design).size(), "Without a wall the tab shows the ring the first order would raise: " + none);
        // A half-built palisade on a smaller stored ring: progress counts that ring, the next order uses the border radius.
        int radius = 20, total = WallGeometry.of(radius, design).size();
        data.putWall(s.id(), new Fortification(1, radius, total / 2, false));
        var wall = dev.livingkingdoms.ui.ManagementService.snapshot(f.player()).orElseThrow().getCompound("wall");
        h.assertTrue(wall.getInt("built") == total / 2 && wall.getInt("total") == total && wall.getInt("ringRadius") == radius
                && wall.getInt("radius") == WallService.radiusFor(s) && wall.getInt("cx") == s.territory().x(), "Rising wall reports built/total/radius: " + wall);
        data.putWall(s.id(), new Fortification(1, radius, total / 2, true));
        wall = dev.livingkingdoms.ui.ManagementService.snapshot(f.player()).orElseThrow().getCompound("wall");
        h.assertTrue(wall.getInt("built") == total, "A finished wall reports every column");
        data.putWall(s.id(), Fortification.none(radius));
        h.succeed();
    }

    private static void supply(ConstructionGameTests.Founded f, Map<ResourceKind, Integer> cost) {
        var inventory = f.player().getInventory(); inventory.clearContent(); int slot = 0;
        for (var e : cost.entrySet()) {
            var item = switch (e.getKey()) { case LOGS -> Items.OAK_LOG; case STONE -> Items.STONE; case IRON_INGOT -> Items.IRON_INGOT; case WHEAT -> Items.WHEAT; };
            for (int left = e.getValue(); left > 0; left -= 64) inventory.items.set(slot++, new ItemStack(item, Math.min(64, left)));
        }
    }
}

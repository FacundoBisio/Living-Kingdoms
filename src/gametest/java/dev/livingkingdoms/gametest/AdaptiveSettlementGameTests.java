package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.SettlementGenerationConfig.Tolerance;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdaptiveSettlementGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void compactCorePlansBeforeApplyAndConnectsModules(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-6144, 0, -6144));
        prepare(level, center, 38);
        var data = SettlementSavedData.get(level.getServer());
        int count = data.settlements().size();
        var plan = plan(level, center, new GenerationDiagnostics());
        var core = plan.buildings().getFirst();
        helper.assertTrue(core.module().size().getX() == 13 && core.module().size().getZ() == 13
                && plan.buildings().size() >= 5, "13x13 core and at least four independent initial modules");
        helper.assertTrue(level.getBlockState(center).isAir() && data.settlements().size() == count,
                "Planning must not mutate terrain or storage");
        for (int i = 0; i < plan.buildings().size(); i++) {
            var building = plan.buildings().get(i);
            helper.assertTrue(building.bounds().inside(plan.territory()), "Every full footprint stays inside territory");
            for (int j = 0; j < i; j++) helper.assertTrue(!building.bounds().conflicts(plan.buildings().get(j).bounds(), 2),
                    "Modules must keep at least two clear blocks between them");
        }
        assertConnected(helper, plan);
        try (var transaction = SettlementPlacement.apply(level, plan)) { transaction.commit(); }
        helper.assertTrue(level.getBlockState(core.position(new BlockPos(6, 1, 9))).is(Blocks.LODESTONE)
                && level.getBlockState(core.position(new BlockPos(9, 1, 9))).is(KingdomBlocks.QUEST_BOARD), "Core interactions exist");
        for (var write : plan.pathBlocks().entrySet()) helper.assertTrue(level.getBlockState(write.getKey()).equals(write.getValue()),
                "Every terrain-following path state is applied");
        helper.assertTrue(level.getBlockState(center.below()).is(Blocks.GRASS_BLOCK), "Core preserves original terrain");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void individualElevationsAndSmallSlopesAreAccepted(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-6656, 0, -6656));
        prepare(level, center, 38);
        // Raise the surrounding terrace, leaving only the compact core at the original level.
        // This guarantees independent elevations regardless of the world's sampling phase.
        for (int x = -38; x <= 38; x++) {
            for (int z = -38; z <= 38; z++) {
                if (x < -6 || x > 6 || z < -9 || z > 3)
                    level.setBlock(center.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
            }
        }
        var plan = plan(level, center, new GenerationDiagnostics());
        helper.assertTrue(plan.buildings().stream().map(building -> building.origin().getY()).distinct().count() > 1,
                "Buildings choose independent elevations");
        helper.assertTrue(plan.pathBlocks().values().stream().anyMatch(state -> state.is(Blocks.COBBLESTONE_STAIRS)),
                "One-block elevation changes use stairs");
        try (var transaction = SettlementPlacement.apply(level, plan)) { transaction.commit(); }
        helper.assertTrue(level.getBlockState(plan.buildings().get(1).origin().below()).is(Blocks.GRASS_BLOCK), "High terrain remains intact under modules");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void waterInOnePlotSelectsAnotherPlot(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-7168, 0, -7168));
        prepare(level, center, 38);
        var original = plan(level, center, new GenerationDiagnostics());
        var house = original.buildings().get(1);
        BlockPos pond = house.origin().offset(1, 0, 1);
        level.setBlock(pond, Blocks.WATER.defaultBlockState(), FLAGS);
        var diagnostics = new GenerationDiagnostics();
        var alternative = plan(level, center, diagnostics);
        helper.assertTrue(diagnostics.summary().plotFailures().getOrDefault(GenerationDiagnostics.Rejection.WATER, 0) > 0,
                "A wet candidate must be classified as water");
        helper.assertTrue(alternative.buildings().stream().noneMatch(building -> building.bounds().contains(pond.getX(), pond.getZ())),
                "Another dry building plot is selected");
        try (var transaction = SettlementPlacement.apply(level, alternative)) { transaction.commit(); }
        helper.assertTrue(level.getBlockState(pond).is(Blocks.WATER), "Local water must remain untouched");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void fourNativeRotationsPreserveDirectionalBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-7680, 0, -7680));
        prepare(level, center, 38);
        var module = catalog(level).get(BuildingKind.BLACKSMITH);
        int index = 0;
        for (Rotation rotation : Rotation.values()) {
            BlockPos minimum = center.offset(-26 + index++ * 14, 0, -8);
            var result = new SettlementLayoutPlanner().planPlot(level, territory(level, center, 48), module,
                    minimum, rotation, new Tolerance(3, 1, 3), new GenerationDiagnostics()).orElseThrow();
            try (var transaction = SettlementPlacement.apply(level, result)) { transaction.commit(); }
            var building = result.buildings().getFirst();
            for (var info : module.blocks()) helper.assertTrue(level.getBlockState(building.position(info.pos())).equals(info.state().rotate(rotation)),
                    "Native rotation must transform all positions and directional states");
            helper.assertTrue(!building.bounds().contains(building.entrance().getX(), building.entrance().getZ()),
                    "Rotated doorstep stays outside the footprint");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void excessiveSlopesTerritoryAndUnavailableChunksAreClassified(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-8192, 0, -8192));
        prepare(level, center, 38);
        var module = catalog(level).get(BuildingKind.HOUSE);
        var planner = new SettlementLayoutPlanner();
        var diagnostics = new GenerationDiagnostics();
        level.setBlock(center.above(6), Blocks.STONE.defaultBlockState(), FLAGS);
        helper.assertTrue(planner.planPlot(level, territory(level, center, 48), module, center, Rotation.NONE,
                new Tolerance(3, 1, 3), diagnostics).isEmpty(), "A cliff/ravine-sized height delta must fail");
        helper.assertTrue(diagnostics.summary().plotFailures().containsKey(GenerationDiagnostics.Rejection.EXCESSIVE_SLOPE), "Slope classification");
        level.setBlock(center.above(6), Blocks.AIR.defaultBlockState(), FLAGS);
        var missingFoundation = new GenerationDiagnostics();
        level.setBlock(center.below(2), Blocks.AIR.defaultBlockState(), FLAGS);
        helper.assertTrue(planner.planPlot(level, territory(level, center, 48), module, center, Rotation.NONE,
                new Tolerance(3, 2, 3), missingFoundation).isEmpty(), "Configured foundation depth refuses a hollow support anchor");
        level.setBlock(center.below(2), Blocks.DIRT.defaultBlockState(), FLAGS);
        var shallowSupports = new GenerationDiagnostics();
        level.setBlock(center.offset(1, 0, 1), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
        helper.assertTrue(planner.planPlot(level, territory(level, center, 48), module, center, Rotation.NONE,
                new Tolerance(3, 1, 0), shallowSupports).isEmpty(), "Support cap is enforced independently of variance");
        var tiny = new GenerationDiagnostics();
        helper.assertTrue(planner.planPlot(level, territory(level, center, 2), module, center.offset(8, 0, 0), Rotation.NONE,
                new Tolerance(3, 1, 3), tiny).isEmpty() && tiny.summary().plotFailures().containsKey(GenerationDiagnostics.Rejection.TERRITORY),
                "Whole footprints must fit the territory");
        BlockPos missing = center.offset(-4096, 0, -4096);
        var unavailable = new GenerationDiagnostics();
        helper.assertTrue(!level.getChunkSource().hasChunk(missing.getX() >> 4, missing.getZ() >> 4), "Unloaded fixture");
        helper.assertTrue(planner.planPlot(level, territory(level, missing, 48), module, missing, Rotation.NONE,
                new Tolerance(3, 1, 3), unavailable).isEmpty()
                && unavailable.summary().plotFailures().containsKey(GenerationDiagnostics.Rejection.UNLOADED_CHUNKS)
                && !level.getChunkSource().hasChunk(missing.getX() >> 4, missing.getZ() >> 4), "Never force-load chunks");
        var border = new GenerationDiagnostics();
        BlockPos beyond = new BlockPos(30_000_000, center.getY(), 30_000_000);
        helper.assertTrue(planner.at(level, SettlementSavedData.get(level.getServer()), catalog(level), beyond, 48, true, border).isEmpty()
                && border.summary().centerFailures().containsKey(GenerationDiagnostics.Rejection.WORLD_BORDER), "Here mode still respects border");
        level.setBlock(center, Blocks.WATER.defaultBlockState(), FLAGS);
        var water = new SettlementGenerator().generateHere(level, center.south(4));
        helper.assertTrue(water.successful() && water.diagnostics().centerFailures().containsKey(GenerationDiagnostics.Rejection.WATER)
                && level.getBlockState(center).is(Blocks.WATER), "Here mode searches beyond the wet first core, preserving water");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void failedSecondModuleRollsBackAllBlocksAndBlockEntities(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-8704, 0, -8704));
        prepare(level, center, 38);
        var original = plan(level, center, new GenerationDiagnostics());
        List<SettlementLayout.Building> buildings = new ArrayList<>(original.buildings());
        var valid = buildings.get(1);
        var broken = new BuildingTemplate(valid.module().kind(), valid.module().id(), new StructureTemplate(),
                valid.module().size(), valid.module().blocks(), valid.module().entrance());
        buildings.set(1, new SettlementLayout.Building(broken, valid.origin(), valid.rotation(), valid.bounds(), valid.entrance(), valid.supports()));
        var failing = new SettlementLayout(original.territory(), original.style(), buildings, original.pathBlocks(), original.before());
        int count = SettlementSavedData.get(level.getServer()).settlements().size();
        boolean failed = false;
        try (var transaction = SettlementPlacement.apply(level, failing)) { transaction.commit(); }
        catch (IllegalStateException expected) { failed = true; }
        helper.assertTrue(failed, "Injected native placement refusal must fail after the core was written");
        for (var snapshot : original.before().entrySet()) helper.assertTrue(level.getBlockState(snapshot.getKey()).equals(snapshot.getValue())
                && level.getBlockEntity(snapshot.getKey()) == null, "Rollback restores exact states and removes new block entities");
        helper.assertTrue(SettlementSavedData.get(level.getServer()).settlements().size() == count, "Failed placement records no settlement");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void stalePlansRefuseBeforeApply(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-9216, 0, -9216));
        prepare(level, center, 38);
        var original = plan(level, center, new GenerationDiagnostics());
        level.setBlock(center, Blocks.CHEST.defaultBlockState(), FLAGS);
        boolean refused = false;
        try (var transaction = SettlementPlacement.apply(level, original)) { transaction.commit(); }
        catch (IllegalStateException expected) { refused = true; }
        helper.assertTrue(refused && level.getBlockState(center).is(Blocks.CHEST), "New protected construction invalidates a stale plan");
        helper.assertTrue(level.getBlockState(original.buildings().getFirst().origin()).isAir(), "No earlier writes before validation ends");
        level.setBlock(center, Blocks.AIR.defaultBlockState(), FLAGS);
        var anchored = plan(level, center, new GenerationDiagnostics());
        level.setBlock(center.below(), Blocks.AIR.defaultBlockState(), FLAGS);
        boolean supportRefused = false;
        try (var transaction = SettlementPlacement.apply(level, anchored)) { transaction.commit(); }
        catch (IllegalStateException expected) { supportRefused = true; }
        helper.assertTrue(supportRefused && level.getBlockState(anchored.buildings().getFirst().origin()).isAir(),
                "Changed natural anchors must invalidate plans before a building can float");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void forestVegetationAndFutureAdditionUseSamePlacement(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(-9728, 0, -9728));
        prepare(level, center, 38);
        level.setBlock(center.offset(4, 0, 0), Blocks.SHORT_GRASS.defaultBlockState(), FLAGS);
        level.setBlock(center.offset(4, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), FLAGS);
        BlockPos trunk = center.offset(20, 0, 20);
        for (int y = 0; y <= 5; y++) level.setBlock(trunk.above(y), Blocks.OAK_LOG.defaultBlockState(), FLAGS);
        var generator = new SettlementGenerator();
        var result = generator.generateAt(level, center);
        helper.assertTrue(result.successful() && level.getBlockState(trunk).is(Blocks.OAK_LOG), "Forest clearing removes foliage locally and preserves trunks");
        var data = SettlementSavedData.get(level.getServer());
        var before = data.layout(result.settlement().id()).orElseThrow();
        var diagnostics = new GenerationDiagnostics();
        var addition = generator.planBuildingAddition(level, result.settlement().id(), BuildingKind.HOUSE, diagnostics)
                .orElseThrow(() -> new IllegalStateException("Addition failed: " + diagnostics.summary()));
        generator.applyBuildingAddition(level, result.settlement().id(), addition);
        helper.assertTrue(data.layout(result.settlement().id()).orElseThrow().buildings().size() == before.buildings().size() + 1
                && data.get(result.settlement().id()).orElseThrow().equals(result.settlement()), "Growth API reserves a new module without changing identity or population");
        helper.succeed();
    }

    private static SettlementLayout plan(ServerLevel level, BlockPos center, GenerationDiagnostics diagnostics) {
        return new SettlementLayoutPlanner().at(level, SettlementSavedData.get(level.getServer()), catalog(level), center,
                48, false, diagnostics).orElseThrow(() -> new IllegalStateException("Plan failed: " + diagnostics.summary()));
    }

    private static BuildingCatalog catalog(ServerLevel level) { return BuildingCatalog.load(level, ArchitectureStyle.PLAINS); }
    private static Territory territory(ServerLevel level, BlockPos center, int radius) {
        return new Territory(level.dimension().location().toString(), center.getX(), center.getY(), center.getZ(), radius);
    }

    private static void prepare(ServerLevel level, BlockPos center, int radius) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++) {
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) level.getChunk(x, z);
        }
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
                for (int y = -3; y < -1; y++) level.setBlock(center.offset(x, y, z), Blocks.DIRT.defaultBlockState(), FLAGS);
                for (int y = 0; y < 13; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    private static void assertConnected(GameTestHelper helper, SettlementLayout plan) {
        var metadata = SettlementLayoutMetadata.from(plan);
        Set<BlockPos> floors = new HashSet<>(metadata.paths());
        floors.addAll(metadata.ports());
        for (int i = 1; i < plan.buildings().size(); i++) {
            Set<BlockPos> visited = new HashSet<>();
            List<BlockPos> pending = new ArrayList<>(List.of(plan.buildings().get(i).entrance()));
            boolean reached = false;
            for (int j = 0; j < pending.size(); j++) {
                BlockPos pos = pending.get(j);
                if (!visited.add(pos)) continue;
                if (metadata.ports().contains(pos)) { reached = true; break; }
                for (BlockPos next : floors) {
                    if (Math.abs(pos.getX() - next.getX()) + Math.abs(pos.getZ() - next.getZ()) == 1
                            && Math.abs(pos.getY() - next.getY()) <= 1 && !visited.contains(next)) pending.add(next);
                }
            }
            helper.assertTrue(reached, "Every major module connects to a plaza port with one-block surface steps");
        }
    }
}

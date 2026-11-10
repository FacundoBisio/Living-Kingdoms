package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.rpg.TownDecorService;
import dev.livingkingdoms.rpg.domain.TownDecor;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.KingdomMapService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 0.33: kingdom stages upgrade every home; the kingdom map snapshot lists what the player should see. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KingdomStageGameTests {
    private static void establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void reachingTownAndCityUpgradesEveryHome(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 231008);
        establish(h, f);
        var level = h.getLevel(); var server = level.getServer(); var id = f.settlement().id();
        var layout = SettlementSavedData.get(server).layout(id).orElseThrow();
        var house = layout.buildings().stream().filter(b -> b.kind().name().startsWith("HOUSE")).findFirst().orElseThrow();
        TownDecorService.beautify(level, id, 9);
        int bits = RpgSavedData.get(server).decor(id);
        for (var tier : new TownDecor[]{TownDecor.VILLAGE_HOMES, TownDecor.TOWN_HOMES, TownDecor.CITY_HOMES})
            h.assertTrue((bits & tier.bit()) != 0, "Home tier applied: " + tier);
        int lanterns = 0, chimneys = 0, skirt = 0;
        var b = house.bounds();
        for (int x = b.minX() - 8; x <= b.maxX() + 8; x++) for (int z = b.minZ() - 8; z <= b.maxZ() + 8; z++) for (int y = house.origin().getY() - 2; y <= house.origin().getY() + 16; y++) {
            var state = level.getBlockState(new BlockPos(x, y, z));
            if (state.is(Blocks.LANTERN)) lanterns++;
            if (state.is(Blocks.CAMPFIRE) && y > house.origin().getY() + 3) chimneys++;
            if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)) skirt++;
        }
        h.assertTrue(lanterns > 0, "A lantern post stands beside the door");
        h.assertTrue(chimneys > 0, "The roof got a smoking chimney");
        h.assertTrue(skirt > 0, "A cobbled skirt surrounds the house");
        h.assertTrue(TownDecorService.beautify(level, id, 20).stream().noneMatch(t -> t.name().endsWith("HOMES")), "Home upgrades apply once");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void mapSnapshotShowsBuildingsResidentsAndStage(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 232032);
        establish(h, f);
        var settlement = SettlementSavedData.get(h.getLevel().getServer()).get(f.settlement().id()).orElseThrow();
        var map = KingdomMapService.snapshot(f.player(), settlement);
        h.assertTrue(map.getInt("radius") > 0 && map.getString("stage").equals("hamlet"), "Territory and stage: " + map.getString("stage"));
        h.assertTrue(map.getList("buildings", 10).size() >= 2, "Town Hall and the first house are on the map");
        h.assertTrue(map.getList("citizens", 10).stream().anyMatch(t -> ((net.minecraft.nbt.CompoundTag) t).contains("px")), "Loaded residents have live positions");
        h.assertTrue(KingdomMapService.open(f.player()), "The map opens from inside the kingdom");
        h.succeed();
    }
}

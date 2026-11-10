package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.ConstructionStages;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageUiService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 0.31 playtest fixes: free residents take any job, the ground never blocks a site, residents can be talked to. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CitizenLifeGameTests {
    private static void establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        h.assertTrue(SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow().lifecycle() == SettlementLifecycle.ESTABLISHED, "Established");
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theFreeResidentBesideTheStartingGuardCanBecomeABuilderAgain(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 221024);
        establish(h, f);
        var server = h.getLevel().getServer(); var id = f.settlement().id(); var jobs = ProfessionSavedData.get(server);
        dev.livingkingdoms.citizen.CitizenService.refreshHomes(h.getLevel(), SettlementSavedData.get(server).get(id).orElseThrow());
        h.assertTrue(jobs.professions(id).stream().anyMatch(p -> p.active() && p.type() == ProfessionType.GUARD), "The starting Guard holds the Town Hall post");
        var free = CitizenSavedData.get(server).citizens(id).stream().filter(c -> c.role() != CitizenRole.MAYOR
                && jobs.profession(c.id()).filter(p -> p.active()).isEmpty()).findFirst().orElseThrow();
        h.assertTrue(free.homeId() != null, "The free resident is housed in the first house");
        h.assertTrue(ProfessionService.assignBuilder(f.player(), id, free.id()), "The free resident becomes a Builder next to the Guard");
        // A Builder retired by the game (e.g. an invalid home for a tick) keeps BUILDER inactive: still free.
        jobs.retire(free.id());
        var retired = jobs.profession(free.id()).orElseThrow();
        h.assertTrue(!retired.active() && retired.type() == ProfessionType.BUILDER, "Retired job stays on record");
        h.assertTrue(dev.livingkingdoms.rpg.ExpeditionService.eligible(server, free.id()), "A retired worker may travel");
        h.assertTrue(ProfessionService.assignBuilder(f.player(), id, free.id()), "A retired Builder can be hired again");
        jobs.retire(free.id());
        h.assertTrue(ProfessionService.assignEconomic(f.player(), id, free.id(), ProfessionType.LUMBERJACK)
                || jobs.buildings(id).stream().noneMatch(b -> b.supports(ProfessionType.LUMBERJACK)), "Or any other job with a workplace");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void talkingToAResidentOpensTheirSheetWithSmallTalk(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 222048);
        establish(h, f);
        var server = h.getLevel().getServer(); var id = f.settlement().id();
        var citizen = CitizenSavedData.get(server).citizens(id).stream().filter(c -> c.role() != CitizenRole.MAYOR).findFirst().orElseThrow();
        var villager = (Villager) h.getLevel().getEntity(citizen.entityId());
        f.player().setPos(villager.getX() + 1, villager.getY(), villager.getZ());
        f.player().snapshots.clear();
        h.assertTrue(VillageUiService.openResident(f.player(), villager, citizen), "Resident sheet opens");
        var snapshot = f.player().snapshots.getLast();
        h.assertTrue(snapshot.getString("screen").equals("citizens") && snapshot.getUUID("focus").equals(citizen.id()), "Roster focused on the resident");
        var key = snapshot.getCompound("talk").getString("key");
        h.assertTrue(key.startsWith("resident.livingkingdoms.chatter.")
                && net.minecraft.locale.Language.getInstance().has(key), "Small talk line exists: " + key);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void weatheredGroundNeverBlocksASite(GameTestHelper h) {
        h.assertTrue(ConstructionStages.groundSwap(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState()), "Grass to dirt is the same ground");
        h.assertTrue(ConstructionStages.groundSwap(Blocks.DIRT.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState()), "A trampled path is the same ground");
        h.assertTrue(!ConstructionStages.groundSwap(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()), "Planks are not ground");
        var f = ConstructionGameTests.found(h, 223072);
        var server = h.getLevel().getServer(); var level = h.getLevel();
        var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
        int weathered = 0;
        for (var pos : entry.plan().before().keySet()) {
            if (level.getBlockState(pos).is(Blocks.GRASS_BLOCK)) { level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2); if (++weathered >= 12) break; }
        }
        h.assertTrue(weathered > 0, "The site has grass to weather");
        h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Weathered ground does not obstruct: " + ConstructionService.reason(entry.project().id()));
        h.assertTrue(ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project().state() == ConstructionState.COMPLETED, "Project completes");
        h.succeed();
    }
}

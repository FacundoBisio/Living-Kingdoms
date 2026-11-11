package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.economy.FoundersLodge;
import dev.livingkingdoms.economy.TavernService;
import dev.livingkingdoms.rpg.ChapterService;
import dev.livingkingdoms.rpg.domain.ChapterProgress;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 0.37: the camp becomes the Founders' Lodge; chapter missions are accepted, tracked and claimed in order. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChapterGameTests {
    private static void establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theCampBecomesTheFoundersLodgeWithTheTownHall(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 551024);
        var level = h.getLevel(); var server = level.getServer();
        var camp = SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow().buildings().stream()
                .filter(b -> b.kind() == BuildingKind.FOUNDING_CAMP).findFirst().orElseThrow();
        var stone = camp.origin().offset(4, 1, 4); var board = camp.origin().offset(7, 1, 4);
        h.assertTrue(!FoundersLodge.converted(server, f.settlement().id()), "Still a camp while founding");
        establish(h, f);
        h.assertTrue(FoundersLodge.converted(server, f.settlement().id()), "The Town Hall turns the camp into the lodge");
        h.assertTrue(level.getBlockState(stone).is(Blocks.LODESTONE) && level.getBlockState(board).is(KingdomBlocks.QUEST_BOARD.get()), "Kingdom stone and board stay in place");
        h.assertTrue(level.getBlockState(camp.origin().offset(4, 4, 4)).isSolid(), "The lodge has a roof");
        h.assertTrue(TavernService.boost(server, f.settlement().id()) > 1, "The lodge works as a small inn");
        var settlement = SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow();
        h.assertTrue(!FoundersLodge.convert(level, settlement), "Converted once");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void chapterMissionsAreAcceptedTrackedAndClaimedInOrder(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 552048);
        var server = h.getLevel().getServer(); var player = f.player(); var data = RpgSavedData.get(server);
        h.assertTrue(!ChapterService.accept(player, 0), "No chapter before the Town Hall");
        establish(h, f);
        h.assertTrue(!ChapterService.accept(player, 1), "Missions unlock in order");
        h.assertTrue(ChapterService.accept(player, 0), "First mission accepted");
        h.assertTrue(!ChapterService.claim(player, 0), "Not before the goal is met");
        data.putChapter(player.getUUID(), new ChapterProgress(0, 0, 15));
        player.getInventory().clearContent();
        h.assertTrue(ChapterService.claim(player, 0), "Hunt claimed at 15 kills");
        h.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 10, "Emerald reward paid");
        h.assertTrue(data.chapter(player.getUUID()).claimed(0) && data.chapter(player.getUUID()).next(12) == 1, "Next mission unlocked");
        h.assertTrue(ChapterService.accept(player, 1) && !ChapterService.claim(player, 1), "Town Hall level 2 still required");
        h.assertTrue(ChapterService.snapshot(player).getList("missions", 10).size() == 12, "Twelve missions in the chapter");
        h.succeed();
    }
}

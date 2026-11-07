package dev.livingkingdoms.gametest;

import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.rpg.ContractService;
import dev.livingkingdoms.rpg.KingdomProgressService;
import dev.livingkingdoms.rpg.domain.Contract;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.ui.KingdomHudService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressionLoopGameTests {
    private static ItemStack material(String target, int count) {
        return new ItemStack(switch (ResourceKind.fromId(target)) {
            case LOGS -> Items.OAK_LOG; case STONE -> Items.STONE; case WHEAT -> Items.WHEAT; case IRON_INGOT -> Items.IRON_INGOT; }, count);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void dailyContractsPayOnceAndBonusExtendsTheStreak(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 195120);
        var player = f.player(); player.getInventory().clearContent();
        var board = ContractService.board(player).orElseThrow();
        h.assertTrue(board.daily().size() == 3, "Three daily contracts for a rank 1 kingdom");
        h.assertTrue(board.weekly() == null, "Weekly contract is locked at rank 1");
        h.assertTrue(!ContractService.claim(player, ContractService.SLOT_BONUS), "Bonus needs every daily first");
        int slot = 0;
        for (var contract : board.daily()) {
            if (contract.kind() == Contract.Kind.DELIVER) player.getInventory().add(material(contract.target(), contract.required()));
            else ContractService.progress(player, contract.kind(), contract.required());
        }
        long xpBefore = RpgSavedData.get(player.server).kingdomXp(f.settlement().id());
        for (int i = 0; i < board.daily().size(); i++) h.assertTrue(ContractService.claim(player, i), "Finished daily " + i + " pays");
        h.assertTrue(!ContractService.claim(player, slot), "A claimed contract cannot pay twice");
        h.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 3 * ContractService.DAILY_EMERALDS, "Emeralds from three contracts");
        for (var contract : board.daily()) if (contract.kind() == Contract.Kind.DELIVER)
            h.assertTrue(player.getInventory().countItem(material(contract.target(), 1).getItem()) == 0, "Delivered materials consumed exactly");
        h.assertTrue(RpgSavedData.get(player.server).kingdomXp(f.settlement().id()) >= xpBefore + 3L * ContractService.DAILY_KINGDOM_XP, "Kingdom XP awarded");
        h.assertTrue(ContractService.claim(player, ContractService.SLOT_BONUS), "Bonus chest after every daily");
        h.assertTrue(ContractService.board(player).orElseThrow().streak() == 1, "Streak started");
        h.assertTrue(!ContractService.claim(player, ContractService.SLOT_BONUS), "Bonus once per day");
        int chests = player.getInventory().countItem(KingdomItems.CHEST_COMMON.get()) + player.getInventory().countItem(KingdomItems.CHEST_RARE.get())
                + player.getInventory().countItem(KingdomItems.CHEST_EPIC.get()) + player.getInventory().countItem(KingdomItems.CHEST_LEGENDARY.get());
        h.assertTrue(chests >= 1, "Bonus and level-up chests delivered");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rankUpPaysAChestAndShowsInTheHud(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 196144);
        var player = f.player(); player.getInventory().clearContent();
        long before = RpgSavedData.get(player.server).kingdomXp(f.settlement().id());
        int gained = KingdomProgressService.award(player, f.settlement().id(), 100 - before + 1);
        h.assertTrue(gained == 1 && KingdomProgressService.level(player.server, f.settlement().id()) == 2, "Kingdom reached rank 2");
        h.assertTrue(player.getInventory().countItem(KingdomItems.CHEST_RARE.get()) == 1, "Rank 2 pays a rare chest");
        var hud = KingdomHudService.snapshot(player).getCompound("settlement");
        h.assertTrue(hud.getInt("rank") == 2 && hud.getLong("rank_span") > 0, "HUD snapshot carries rank and progress");
        h.assertTrue(ContractService.board(player).orElseThrow().weekly() != null, "Weekly contract unlocks at rank 2");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void openingAChestConsumesItAndPaysItsRarity(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 197168);
        var player = f.player(); player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(KingdomItems.CHEST_EPIC.get(), 2));
        KingdomItems.CHEST_EPIC.get().use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        h.assertTrue(player.getMainHandItem().getCount() == 1, "One chest consumed per use");
        h.assertTrue(player.getInventory().countItem(Items.DIAMOND) >= 2 && player.getInventory().countItem(KingdomItems.ROYAL_WHETSTONE.get()) >= 2
                && player.getInventory().countItem(Items.ENCHANTED_BOOK) >= 1, "Epic chest pays diamonds, whetstones and a tome");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void contractsCannotBeClaimedOutsideTheKingdom(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 198192);
        var player = f.player();
        var board = ContractService.board(player).orElseThrow();
        for (var c : board.daily()) if (c.tracked()) ContractService.progress(player, c.kind(), c.required());
        int slot = board.daily().indexOf(board.daily().stream().filter(Contract::tracked).findFirst().orElseThrow());
        player.setPos(player.getX() + 5000, player.getY(), player.getZ());
        h.assertTrue(!ContractService.claim(player, slot), "Claims require standing in your kingdom");
        h.assertTrue(!RpgSavedData.get(player.server).board(player.getUUID()).daily().get(slot).claimed(), "Refused claim leaves the receipt untouched");
        h.succeed();
    }
}

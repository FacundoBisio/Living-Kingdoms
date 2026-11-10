package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.CitizenConfig;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.economy.BuildingHearts;
import dev.livingkingdoms.economy.BuildingVisuals;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.economy.TavernService;
import dev.livingkingdoms.economy.WarehouseService;
import dev.livingkingdoms.profession.GuardEquipment;
import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.ui.ManagementService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 0.35: building ledgers, visual upgrades, the Tavern, a working Warehouse and guard roles. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildingLifeGameTests {
    private static Settlement establish(GameTestHelper h, ConstructionGameTests.Founded f) {
        var server = h.getLevel().getServer();
        for (int i = 0; i < 2; i++) {
            var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
            h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), "Founding project " + i + " completes");
        }
        return SettlementSavedData.get(server).get(f.settlement().id()).orElseThrow();
    }
    private static FunctionalBuilding build(GameTestHelper h, ConstructionGameTests.Founded f, BuildingKind kind) {
        var server = h.getLevel().getServer();
        h.assertTrue(ConstructionService.planEconomicBuilding(h.getLevel(), f.settlement().id(), f.player(), kind), "Planned " + kind);
        var entry = ConstructionService.current(server, f.settlement().id()).orElseThrow();
        h.assertTrue(ConstructionService.debugAdvance(server, entry.project().id(), 4, f.player()), kind + " completes");
        return ProfessionSavedData.get(server).buildings(f.settlement().id()).stream().filter(b -> b.kind() == kind).findFirst().orElseThrow();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everyBuildingGetsALedgerAndUpgradesShowOnTheBuilding(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 351024);
        var s = establish(h, f); var level = h.getLevel();
        var hall = ProfessionSavedData.get(level.getServer()).buildings(s.id()).stream().filter(b -> b.kind() == BuildingKind.TOWN_HALL).findFirst().orElseThrow();
        BuildingHearts.ensure(level, s);
        var desk = BuildingHearts.find(level, hall);
        h.assertTrue(desk != null && level.getBlockState(desk).is(KingdomBlocks.BUILDING_LEDGER.get()), "The Town Hall has its ledger");
        h.assertTrue(BuildingHearts.ensure(level, s) == 0, "Ledgers are placed once");
        h.assertTrue(BuildingHearts.at(level.getServer(), s, desk).id().equals(hall.id()), "The ledger knows its building");
        f.player().setPos(desk.getX() + .5, desk.getY(), desk.getZ() + 1.5);
        h.assertTrue(ManagementService.open(f.player(), s.id(), hall.id())
                && ManagementService.snapshot(f.player()).orElseThrow().getUUID("focus").equals(hall.id()), "Ledger opens management focused on its building");
        int cobble = count(level, hall, Blocks.COBBLESTONE) + count(level, hall, Blocks.OAK_PLANKS);
        h.assertTrue(cobble > 0, "The Town Hall has cobblestone or oak to refine");
        BuildingVisuals.apply(level, hall, 2);
        h.assertTrue(count(level, hall, Blocks.COBBLESTONE) + count(level, hall, Blocks.OAK_PLANKS) == 0, "Level 2 refines every cobblestone and oak plank");
        BuildingVisuals.apply(level, hall, 3);
        h.assertTrue(count(level, hall, Blocks.DEEPSLATE_BRICKS) + count(level, hall, Blocks.DARK_OAK_PLANKS) > 0, "Level 3 turns stately");
        h.assertTrue(level.getBlockState(desk).is(KingdomBlocks.BUILDING_LEDGER.get()), "The ledger survives upgrades");
        h.succeed();
    }

    private static int count(net.minecraft.server.level.ServerLevel level, FunctionalBuilding b, net.minecraft.world.level.block.Block block) {
        int n = 0; var box = b.bounds();
        for (int y = b.origin().getY(); y <= b.origin().getY() + 13; y++) for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++)
            if (level.getBlockState(new BlockPos(x, y, z)).is(block)) n++;
        return n;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void tavernHiresTravellersForEmeraldsWhenABedIsFree(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 352048);
        var s = establish(h, f); var server = h.getLevel().getServer();
        var tavern = build(h, f, BuildingKind.TAVERN);
        h.assertTrue(TavernService.boost(server, s.id()) > 1, "A tavern makes travellers more likely");
        var player = f.player(); player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 20));
        int capacity = CitizenConfig.DEFAULT_HOUSE_CAPACITY.get();
        try {
            CitizenConfig.DEFAULT_HOUSE_CAPACITY.set(4);
            CitizenSavedData.get(server).synchronizeHousing(s, SettlementSavedData.get(server).layout(s.id()).orElseThrow(), CitizenConfig::capacity);
            int before = CitizenSavedData.get(server).population(s.id());
            h.assertTrue(TavernService.hire(player, s.id(), tavern.id()), "A traveller is hired");
            h.assertTrue(CitizenSavedData.get(server).population(s.id()) == before + 1, "Population grows by one");
            h.assertTrue(player.getInventory().countItem(Items.EMERALD) == 20 - TavernService.price(1), "Emeralds paid once");
        } finally { CitizenConfig.DEFAULT_HOUSE_CAPACITY.set(capacity); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void warehouseChestsFeedKingdomStorage(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 353072);
        var s = establish(h, f); var level = h.getLevel(); var server = level.getServer();
        var warehouse = build(h, f, BuildingKind.WAREHOUSE);
        Container barrel = null; var box = warehouse.bounds();
        for (int y = warehouse.origin().getY(); y <= warehouse.origin().getY() + 6 && barrel == null; y++)
            for (int x = box.minX(); x <= box.maxX() && barrel == null; x++) for (int z = box.minZ(); z <= box.maxZ() && barrel == null; z++)
                if (level.getBlockEntity(new BlockPos(x, y, z)) instanceof Container c) barrel = c;
        h.assertTrue(barrel != null, "The warehouse has barrels");
        barrel.setItem(0, new ItemStack(Items.OAK_LOG, 20)); barrel.setItem(1, new ItemStack(Items.DIRT, 5));
        int wood = KingdomEconomy.count(server, s.id(), StockResource.WOOD);
        h.assertTrue(WarehouseService.sweep(level, warehouse) == 20, "Logs move into storage");
        h.assertTrue(KingdomEconomy.count(server, s.id(), StockResource.WOOD) == wood + 20 && barrel.getItem(0).isEmpty(), "Storage gained the logs, barrel emptied");
        h.assertTrue(barrel.getItem(1).is(Items.DIRT), "Other items stay");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void guardsSwitchBetweenPatrolAndArcher(GameTestHelper h) {
        var f = ConstructionGameTests.found(h, 354096);
        var s = establish(h, f); var server = h.getLevel().getServer();
        var job = ProfessionSavedData.get(server).professions(s.id()).stream().filter(p -> p.active() && p.type() == ProfessionType.GUARD).findFirst().orElseThrow();
        var guard = (Villager) h.getLevel().getEntity(CitizenSavedData.get(server).citizen(job.citizenId()).orElseThrow().entityId());
        GuardEquipment.apply(guard, job);
        h.assertTrue(guard.getMainHandItem().is(Items.IRON_SWORD), "Patrols carry a sword");
        GuardEquipment.setArcher(guard, job, true, f.player());
        h.assertTrue(GuardEquipment.archer(guard) && guard.getMainHandItem().is(Items.BOW) && GuardEquipment.issued(guard.getMainHandItem()), "Archers get an issued bow");
        GuardEquipment.setArcher(guard, job, false, f.player());
        h.assertTrue(!GuardEquipment.archer(guard) && guard.getMainHandItem().is(Items.IRON_SWORD), "Back to patrol with a sword");
        h.succeed();
    }
}

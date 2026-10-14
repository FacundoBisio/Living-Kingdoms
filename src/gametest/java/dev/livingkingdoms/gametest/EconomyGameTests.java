package dev.livingkingdoms.gametest;

import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EconomyGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void realDepositsWithdrawalsAndDiskReloadNeverDuplicateItems(GameTestHelper h) {
        var s=settlement(h,881600);var player=player(h,s);var server=h.getLevel().getServer();
        player.getInventory().setItem(0,new ItemStack(Items.WHEAT,16));player.getInventory().setItem(1,new ItemStack(Items.BIRCH_LOG,32));
        h.assertTrue(KingdomEconomy.deposit(player,s.id(),StockResource.FOOD,64)==16
                && KingdomEconomy.deposit(player,s.id(),StockResource.WOOD,64)==32,"Native carried items enter one shared store");
        h.assertTrue(player.getInventory().isEmpty() && KingdomEconomy.count(server,s.id(),StockResource.WOOD)==32
                && ProfessionService.food(server,s.id()).produced()==0,"Deposits consume inputs and do not forge harvest progress");
        var before=KingdomEconomy.plan(server,s.id(),Map.of(StockResource.WOOD,2),Map.of(StockResource.STONE,4),true).orElseThrow();
        var stale=KingdomEconomy.plan(server,s.id(),Map.of(StockResource.WOOD,2),Map.of(StockResource.STONE,4),true).orElseThrow();
        h.assertTrue(KingdomEconomy.commit(server,before) && !KingdomEconomy.commit(server,stale),"Multiplayer/stale production cannot commit twice");
        for(int i=0;i<player.getInventory().items.size();i++) player.getInventory().items.set(i,new ItemStack(Items.DIRT,64));
        h.assertTrue(KingdomEconomy.withdraw(player,s.id(),StockResource.WOOD,8)==0 && KingdomEconomy.count(server,s.id(),StockResource.WOOD)==30,"Full inventory leaves all materials in storage");
        player.getInventory().items.set(0,ItemStack.EMPTY);
        h.assertTrue(KingdomEconomy.withdraw(player,s.id(),StockResource.WOOD,8)==8 && player.getInventory().getItem(0).is(Items.OAK_LOG)
                && player.getInventory().getItem(0).getCount()==8 && KingdomEconomy.count(server,s.id(),StockResource.WOOD)==22,"Canonical wood units withdraw exactly once");
        var expected=EconomySavedData.get(server).account(s.id()).orElseThrow();server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var storage=new DimensionDataStorage(server.getWorldPath(LevelResource.ROOT).resolve("data").toFile(),null,h.getLevel().registryAccess());
        var reloaded=storage.get(new SavedData.Factory<>(EconomySavedData::new,EconomySavedData::load),EconomySavedData.DATA_NAME);
        h.assertTrue(reloaded!=null && reloaded.account(s.id()).orElseThrow().equals(expected),"Real disk save/reopen preserves material totals and production receipts");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=3000)
    public static void economicPermissionsAndPeriodicFoodReceiptAreServerOwned(GameTestHelper h) {
        var s=settlement(h,882624);var player=player(h,s);var server=h.getLevel().getServer();
        player.getInventory().setItem(0,new ItemStack(Items.WHEAT,16));
        var stranger=new UiTestPlayer(h.getLevel());stranger.setPos(s.territory().x()+.5,s.territory().y(),s.territory().z()+.5);
        stranger.getInventory().setItem(0,new ItemStack(Items.WHEAT,16));
        h.assertTrue(KingdomEconomy.deposit(stranger,s.id(),StockResource.FOOD,16)==0,"A nearby player without joining cannot mutate stock");
        h.assertTrue(KingdomEconomy.deposit(player,s.id(),StockResource.FOOD,16)==16,"Joined citizen deposits food normally");
        h.assertTrue(!KingdomEconomy.consume(server,s),"New realms receive a full initial consumption interval");
        h.runAfterDelay(dev.livingkingdoms.config.EconomyConfig.CONSUMPTION_INTERVAL.get()+1,()-> {
            h.assertTrue(KingdomEconomy.consume(server,s) && !KingdomEconomy.consume(server,s),"Each elapsed consumption receipt is applied once");
            h.assertTrue(ProfessionService.food(server,s.id()).stock()==16*dev.livingkingdoms.config.ProfessionConfig.WHEAT_FOOD.get()-2 && ProfessionService.food(server,s.id()).produced()==0,"Population eight consumes two nutrition units without changing harvest totals");
            player.setPos(s.territory().x()+100,s.territory().y(),s.territory().z());
            h.assertTrue(KingdomEconomy.withdraw(player,s.id(),StockResource.FOOD,1)==0,"Remote and wrong-territory withdrawal is refused");h.succeed();
        });
    }
    private static Settlement settlement(GameTestHelper h,int offset) {
        var center=h.absolutePos(new BlockPos(offset,1,offset));
        var s=Settlement.established(UUID.randomUUID(),new Territory(h.getLevel().dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),
                8,SettlementOrigin.GENERATED,UUID.randomUUID());SettlementSavedData.get(h.getLevel().getServer()).add(s);return s;
    }
    private static UiTestPlayer player(GameTestHelper h,Settlement settlement) {
        var player=new UiTestPlayer(h.getLevel());player.setPos(settlement.territory().x()+.5,settlement.territory().y(),settlement.territory().z()+.5);
        OnboardingTestSupport.legacyCollaborator(player,settlement);return player;
    }
}

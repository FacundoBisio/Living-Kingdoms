package dev.livingkingdoms.economy;

import dev.livingkingdoms.profession.domain.FoodStock;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EconomyTest {
    @Test void rewardedFoodCannotBeRefundedOrLaunderedThroughLaterHarvestsAndMigrationKeepsOldBalances() {
        var data=new dev.livingkingdoms.profession.persistence.ProfessionSavedData();var id=UUID.randomUUID();data.ensureFood(id,500);assertEquals(80,data.pledgeFood(id,80));
        var gift=data.food(id,500);assertEquals(80,gift.pledged());assertEquals(0,gift.surplus(60));assertFalse(data.exchangeFood(id,gift,2,0,true));
        data.addFood(id,2);var withHarvest=data.food(id,500);assertEquals(2,withHarvest.surplus(60));assertTrue(data.exchangeFood(id,withHarvest,2,0,true));
        var remaining=data.food(id,500);assertEquals(new FoodStock(80,500,82,80),remaining);assertFalse(data.exchangeFood(id,remaining,2,0,true));
        assertTrue(data.exchangeFood(id,remaining,1,0));assertEquals(79,data.food(id,500).pledged());
        var saved=data.save(new CompoundTag(),null);var reloaded=dev.livingkingdoms.profession.persistence.ProfessionSavedData.load(saved,null);assertEquals(data.food(id,500),reloaded.food(id,500));
        var legacy=saved.copy();legacy.putInt("schema_version",3);legacy.getList("food",10).getCompound(0).remove("pledged");
        var migrated=dev.livingkingdoms.profession.persistence.ProfessionSavedData.load(legacy,null);assertEquals(79,migrated.food(id,500).stock());assertEquals(0,migrated.food(id,500).pledged());
        var invalid=saved.copy();invalid.getList("food",10).getCompound(0).remove("pledged");assertThrows(IllegalArgumentException.class,()->dev.livingkingdoms.profession.persistence.ProfessionSavedData.load(invalid,null));
    }
    @Test void communalFoodSurplusRetainsHealthyRationsWithoutDeletingStockOrBlockingInternalUse() {
        var food=new FoodStock(80,500,80);assertEquals(20,food.surplus(60));assertEquals(0,food.surplus(100));
        assertEquals(0,new FoodStock(20,20,0).surplus(60));assertEquals(80,food.stock());
        assertEquals(0,new FoodStock(59,500,0).surplus(60));assertEquals(79,food.consume(1).stock());
        assertThrows(IllegalArgumentException.class,()->food.surplus(-1));
    }
    @Test void nutritionConversionConservesWheatAcrossDepositsQuestSuppliesAndWithdrawals() {
        for(int nutrition:new int[]{1,2,100}) for(int items=0;items<=64;items++) {
            int units=StockResource.FOOD.unitsForItems(items,nutrition);
            assertEquals(items,StockResource.FOOD.itemsFromUnits(units,nutrition));
            for(var material:StockResource.values()) if(material!=StockResource.FOOD) assertEquals(items,material.unitsForItems(items,nutrition));
        }
        assertEquals(0,StockResource.FOOD.itemsFromUnits(1,2));assertEquals(2,StockResource.FOOD.itemsFromUnits(5,2));
        assertEquals(4,StockResource.FOOD.unitsForItems(StockResource.FOOD.itemsFromUnits(5,2),2));
        assertThrows(IllegalArgumentException.class,()->StockResource.FOOD.unitsPerItem(0));
        assertThrows(ArithmeticException.class,()->StockResource.FOOD.unitsForItems(Integer.MAX_VALUE,100));
    }
    @Test void inputConsumptionAndProductionAreBoundedAndAtomic() {
        var initial=StockAccount.empty(64,10).exchange(Map.of(),Map.of(StockResource.WOOD,32),false).orElseThrow();
        assertTrue(initial.produced().isEmpty());
        assertTrue(initial.exchange(Map.of(StockResource.WOOD,33),Map.of(StockResource.IRON,1),true).isEmpty());
        assertEquals(32,initial.balance(StockResource.WOOD));
        var crafted=initial.exchange(Map.of(StockResource.WOOD,2),Map.of(StockResource.IRON,1),true).orElseThrow();
        assertEquals(30,crafted.balance(StockResource.WOOD));assertEquals(1,crafted.balance(StockResource.IRON));assertEquals(1L,crafted.produced().get(StockResource.IRON));
        assertTrue(crafted.exchange(Map.of(),Map.of(StockResource.IRON,64),true).isEmpty());
        assertThrows(IllegalArgumentException.class,()->initial.exchange(Map.of(),Map.of(StockResource.FOOD,1),true));
    }
    @Test void loweringCapacityBlocksNewDepositsWithoutDeletingExistingItems() {
        var full=StockAccount.empty(128,0).exchange(Map.of(),Map.of(StockResource.STONE,100),false).orElseThrow().capacity(64);
        assertEquals(100,full.balance(StockResource.STONE));assertEquals(0,full.room(StockResource.STONE));
        assertTrue(full.exchange(Map.of(),Map.of(StockResource.STONE,1),false).isEmpty());
        assertEquals(36,full.exchange(Map.of(StockResource.STONE,64),Map.of(),false).orElseThrow().balance(StockResource.STONE));
    }
    @Test void existingFoodSeparatesHarvestsFromDepositsAndConsumption() {
        var harvested=new FoodStock(0,40,0).add(5);var deposited=harvested.deposit(10);
        assertEquals(15,deposited.stock());assertEquals(5,deposited.produced());
        assertEquals(new FoodStock(12,40,5),deposited.consume(3));
        assertThrows(IllegalArgumentException.class,()->deposited.consume(16));
    }
    @Test void staleMultiplayerReceiptsCannotCommitTwiceAndReloadPreservesCounters() {
        var data=new EconomySavedData();UUID settlement=UUID.randomUUID();var before=data.ensure(settlement,64,50);
        var next=before.exchange(Map.of(),Map.of(StockResource.WOOD,8),true).orElseThrow();
        assertTrue(data.replace(settlement,before,next));assertFalse(data.replace(settlement,before,next));
        var consumed=next.consume(2500,2);assertTrue(data.replace(settlement,next,consumed));
        var loaded=EconomySavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(consumed,loaded.account(settlement).orElseThrow());assertFalse(loaded.isDirty());
        assertEquals(8,loaded.account(settlement).orElseThrow().balance(StockResource.WOOD));
        assertEquals(2,loaded.account(settlement).orElseThrow().consumedFood());
    }
    @Test void futureOrCorruptSchemasAndDuplicateAccountsAreRejected() {
        var data=new EconomySavedData();data.ensure(UUID.randomUUID(),64,0);var original=data.save(new CompoundTag(),null);
        var future=original.copy();future.putInt("schema_version",3);assertThrows(IllegalArgumentException.class,()->EconomySavedData.load(future,null));
        var corrupt=original.copy();corrupt.getList("accounts",10).getCompound(0).putInt("balance_WOOD",-1);
        assertThrows(IllegalArgumentException.class,()->EconomySavedData.load(corrupt,null));
        var duplicate=original.copy();var list=duplicate.getList("accounts",10);list.add(list.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class,()->EconomySavedData.load(duplicate,null));
    }
}

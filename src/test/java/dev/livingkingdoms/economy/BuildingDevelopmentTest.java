package dev.livingkingdoms.economy;

import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuildingDevelopmentTest {
    @Test void buildingLevelsAndRepairOrdersSurviveEveryEconomicOperationAndReload() {
        var data=new EconomySavedData();UUID realm=UUID.randomUUID(),building=UUID.randomUUID();var account=data.ensure(realm,256,20);
        var upgraded=account.upgrade(building).repair(building,true);
        upgraded=upgraded.exchange(Map.of(),Map.of(StockResource.WOOD,50),true).orElseThrow().consume(2600,2).capacity(512);
        // The store accepts consecutive revision changes, each checked before its commit.
        var persisted=new StockAccount(upgraded.balances(),upgraded.produced(),upgraded.capacity(),account.revision()+1,upgraded.consumedAt(),upgraded.consumedFood(),upgraded.buildingLevels(),upgraded.repairOrders());
        assertTrue(data.replace(realm,account,persisted));var loaded=EconomySavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(persisted,loaded.account(realm).orElseThrow());assertEquals(2,persisted.buildingLevel(building));assertTrue(persisted.repairOrders().contains(building));
        assertFalse(persisted.repair(building,false).repairOrders().contains(building));
    }
    @Test void firstEconomySchemaMigratesWithoutLosingStoredResources() {
        var data=new EconomySavedData();UUID realm=UUID.randomUUID();var initial=data.ensure(realm,256,10);
        data.replace(realm,initial,initial.exchange(Map.of(),Map.of(StockResource.IRON,16),false).orElseThrow());
        var legacy=data.save(new CompoundTag(),null);legacy.putInt("schema_version",1);
        for(var entry:legacy.getList("accounts",10)) {((CompoundTag)entry).remove("building_levels");((CompoundTag)entry).remove("repair_orders");}
        var restored=EconomySavedData.load(legacy,null).account(realm).orElseThrow();assertEquals(16,restored.balance(StockResource.IRON));assertTrue(restored.buildingLevels().isEmpty());
    }
    @Test void paidTerritoryGrowthPreservesIdentityAndUsesTheExistingLocationIndex() {
        var data=new SettlementSavedData();var s=realm(0,0);data.add(s);assertTrue(data.at("minecraft:overworld",60,0).isEmpty());
        assertTrue(data.canExpand(s,64));assertTrue(data.expand(s,64));var next=data.get(s.id()).orElseThrow();
        assertEquals(s.id(),next.id());assertEquals(s.name(),next.name());assertEquals(s.provenance(),next.provenance());assertEquals(64,next.territory().radius());
        assertEquals(next,data.at("minecraft:overworld",60,0).orElseThrow());assertFalse(data.expand(s,80));assertFalse(data.expand(next,48));
        var neighbor=realm(150,0);data.add(neighbor);assertFalse(data.canExpand(next,128));assertFalse(data.expand(next,128));assertEquals(next,data.get(next.id()).orElseThrow());
        var loaded=SettlementSavedData.load(data.save(new CompoundTag(),null),null);assertEquals(next,loaded.at("minecraft:overworld",60,0).orElseThrow());
    }
    @Test void upgradesHaveConcreteCostsAndOnlyFunctionalTargets() {
        assertEquals(64,BuildingDevelopment.upgradeCost(2).get(StockResource.WOOD));
        assertTrue(BuildingDevelopment.upgradeable(BuildingKind.WAREHOUSE));assertFalse(BuildingDevelopment.upgradeable(BuildingKind.HOUSE));
        var account=StockAccount.empty(64,0);UUID id=UUID.randomUUID();for(int i=0;i<4;i++) account=account.upgrade(id);
        var capped=account;assertThrows(IllegalArgumentException.class,()->capped.upgrade(id));
    }
    private static Settlement realm(int x,int z) { return Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",x,64,z,48),2,SettlementOrigin.GENERATED,UUID.randomUUID()); }
}

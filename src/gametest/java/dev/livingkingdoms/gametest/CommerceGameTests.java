package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.commerce.*;
import dev.livingkingdoms.config.CommerceConfig;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommerceGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_commerce_test",Comparator.comparingLong(ChunkPos::toLong),600);
    @GameTest(template="empty",timeoutTicks=600)
    public static void realMerchantTradesAtomicallyRejectReplayStaleQuotesAndPersistTreasury(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var center=h.absolutePos(new BlockPos(899072,1,899072));int radius=32;
        for(int x=(center.getX()-48)>>4;x<=(center.getX()+48)>>4;x++)for(int z=(center.getZ()-48)>>4;z<=(center.getZ()+48)>>4;z++)level.getChunk(x,z);
        var chunk=new ChunkPos(center);level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);for(int y=0;y<13;y++)level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);}
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);var buildings=new ArrayList<SettlementLayoutMetadata.Building>();
        for(int i=0;i<3;i++) {
            var module=catalog.get(i==2?BuildingKind.MARKET:BuildingKind.HOUSE);var at=center.offset(i==2?-18:12,-1,i==1?-16:12);
            module.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
            buildings.add(new SettlementLayoutMetadata.Building(module.kind(),module.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+module.size().getX()-1,at.getZ()+module.size().getZ()-1),at.offset(module.entrance())));
        }
        SettlementSavedData.get(server).add(settlement,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of()));level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        dev.livingkingdoms.npc.NpcService.ensureMayor(level,settlement).orElseThrow();ProfessionService.ensure(level,settlement);level.setDayTime(6000);
        var player=new UiTestPlayer(level);player.setPos(center.getX()+.5,center.getY(),center.getZ()+.5);OnboardingTestSupport.legacyCollaborator(player,settlement);
        var candidate=ImmigrationService.attempt(level,settlement,true).orElseThrow();h.assertTrue(ImmigrationService.accept(player,settlement.id(),candidate.id()),"Merchant joins through normal immigration");
        var citizen=CitizenSavedData.get(server).citizen(candidate.id()).orElseThrow();var worker=(Villager)level.getEntity(citizen.entityId());ProfessionService.ensure(level,settlement);
        h.assertTrue(ProfessionService.assignMerchant(player,settlement.id(),citizen.id()),"Real citizen assigned to functional market");
        var jobs=ProfessionSavedData.get(server);var workplace=jobs.building(jobs.profession(citizen.id()).orElseThrow().workplaceId()).orElseThrow();var point=workplace.entrance().above();
        worker.setPos(point.getX()+.5,point.getY(),point.getZ()+.5);player.setPos(point.getX()+1.5,point.getY(),point.getZ()+.5);
        h.assertTrue(MerchantWork.stationPresent(level,workplace) && MerchantWork.available(worker),"Native barrel and lectern enable market work");
        KingdomEconomy.commit(server,KingdomEconomy.plan(server,settlement.id(),Map.of(),Map.of(StockResource.WOOD,6),false).orElseThrow());
        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,20));h.assertTrue(CommerceService.open(player,worker),"Ordinary player opens original merchant interface");
        var other=new UiTestPlayer(level);other.setPos(player.getX(),player.getY(),player.getZ());OnboardingTestSupport.legacyCollaborator(other,settlement);other.getInventory().setItem(0,new ItemStack(Items.EMERALD,20));
        h.assertTrue(CommerceService.open(other,worker),"Two players independently receive quotes");var stale=request(other,StockResource.WOOD,true);var buy=request(player,StockResource.WOOD,true);
        int treasury=CommerceSavedData.get(server).account(settlement.id()).orElseThrow().emeralds();
        h.assertTrue(!CommerceService.handle(other,buy),"Player-bound session cannot be stolen");
        h.assertTrue(CommerceService.handle(player,buy) && !CommerceService.handle(player,buy),"Purchase commits once and replay is rejected");
        h.assertTrue(KingdomEconomy.count(server,settlement.id(),StockResource.WOOD)==5 && count(player,Items.OAK_LOG)==1 && count(player,Items.EMERALD)==20-CommerceConfig.price(StockResource.WOOD,true),"Goods and real emeralds exchange with kingdom stock");
        h.assertTrue(!CommerceService.handle(other,stale) && count(other,Items.EMERALD)==20,"Stale concurrent quote changes no inventory");
        h.assertTrue(CommerceService.handle(player,request(player,StockResource.WOOD,false)),"Player sells canonical purchased item back");
        h.assertTrue(KingdomEconomy.count(server,settlement.id(),StockResource.WOOD)==6 && count(player,Items.OAK_LOG)==0
                && CommerceSavedData.get(server).account(settlement.id()).orElseThrow().emeralds()==treasury+CommerceConfig.price(StockResource.WOOD,true)-CommerceConfig.price(StockResource.WOOD,false),"Buy/sell spread conserves emeralds and cannot profit on round trips");
        var food=ProfessionService.food(server,settlement.id());int healthy=ProfessionConfig.HEALTHY_FOOD.get(),units=ProfessionConfig.WHEAT_FOOD.get();
        h.assertTrue(jobs.exchangeFood(settlement.id(),food,food.stock(),healthy+units),"Fixture has exactly one tradeable food item above the healthy reserve");
        h.assertTrue(!CommerceService.handle(player,request(player,StockResource.FOOD,true)),"Food changes invalidate a quote even without a non-food ledger revision");
        h.assertTrue(CommerceService.handle(player,request(player,StockResource.FOOD,true)),"Food above reserve can be purchased");
        var reserved=CommerceSavedData.get(server).account(settlement.id()).orElseThrow();int emeralds=count(player,Items.EMERALD);
        h.assertTrue(!CommerceService.handle(player,request(player,StockResource.FOOD,true)) && count(player,Items.EMERALD)==emeralds
                && CommerceSavedData.get(server).account(settlement.id()).orElseThrow().equals(reserved)
                && ProfessionService.food(server,settlement.id()).stock()==healthy,"Healthy food reserve refuses atomically");
        food=ProfessionService.food(server,settlement.id());jobs.exchangeFood(settlement.id(),food,food.stock(),0);jobs.pledgeFood(settlement.id(),healthy+units);
        h.assertTrue(!CommerceService.handle(player,request(player,StockResource.FOOD,true)),"New food pledge invalidates previous quotes");
        h.assertTrue(!CommerceService.handle(player,request(player,StockResource.FOOD,true)) && count(player,Items.EMERALD)==emeralds
                && ProfessionService.food(server,settlement.id()).pledged()==healthy+units,"Rewarded community food cannot be sold even above the healthy reserve");
        for(int i=0;i<player.getInventory().items.size();i++)player.getInventory().items.set(i,new ItemStack(Items.DIRT,64));player.getInventory().items.set(0,new ItemStack(Items.EMERALD,64));
        var before=CommerceSavedData.get(server).account(settlement.id()).orElseThrow();
        h.assertTrue(!CommerceService.handle(player,request(player,StockResource.WOOD,true)) && count(player,Items.EMERALD)==64 && CommerceSavedData.get(server).account(settlement.id()).orElseThrow().equals(before),"Full inventory refuses without consuming treasury, quota or emeralds");
        var reloaded=CommerceSavedData.load(CommerceSavedData.get(server).save(new CompoundTag(),level.registryAccess()),level.registryAccess());h.assertTrue(reloaded.account(settlement.id()).orElseThrow().equals(before),"NBT roundtrip preserves finite trade usage");
        server.overworld().getDataStorage().save();net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var storage=new DimensionDataStorage(server.getWorldPath(LevelResource.ROOT).resolve("data").toFile(),null,level.registryAccess());
        var disk=storage.get(new SavedData.Factory<>(CommerceSavedData::new,CommerceSavedData::load),CommerceSavedData.DATA_NAME);
        h.assertTrue(disk!=null && disk.account(settlement.id()).orElseThrow().equals(before),"Disk reopen does not restock or mint emeralds");
        var fresh=CommerceSavedData.get(server).ensure(settlement.id(),before.restockAt());h.assertTrue(fresh.emeralds()==before.emeralds() && fresh.bought().isEmpty() && fresh.sold().isEmpty(),"Restock renews quota only");
        var blocked=request(other,StockResource.WOOD,true);level.setBlock(workplace.origin().offset(2,1,2),Blocks.AIR.defaultBlockState(),18);
        h.assertTrue(!CommerceService.handle(other,blocked) && CommerceService.snapshot(other).isEmpty(),"Destroyed market invalidates live session before inventory mutation");
        h.assertTrue(ProfessionService.remove(player,settlement.id(),citizen.id()),"Merchant career can be removed normally");
        h.assertTrue(jobs.profession(citizen.id()).orElseThrow().type()==ProfessionType.UNASSIGNED,"Citizen identity survives removal");h.succeed();
    }
    private static CommercePayloads.Request request(UiTestPlayer player,StockResource resource,boolean buy){var data=CommerceService.snapshot(player).orElseThrow();return new CommercePayloads.Request(data.getUUID("session"),data.getUUID("offer"),resource,buy?CommercePayloads.Action.BUY:CommercePayloads.Action.SELL);}
    private static int count(UiTestPlayer player,Item item){return player.getInventory().items.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
}

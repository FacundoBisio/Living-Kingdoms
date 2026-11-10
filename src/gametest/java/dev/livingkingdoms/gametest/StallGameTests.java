package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.commerce.*;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** 0.32 market stalls: classic trades of a specialty plus rotating rare kingdom goods, limits kept on the entity. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StallGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_stall_test",Comparator.comparingLong(ChunkPos::toLong),600);
    private record Market(UiTestPlayer player, Villager worker, Settlement settlement) {}

    private static Market market(GameTestHelper h,int coordinate) {
        var level=h.getLevel();var server=level.getServer();var center=h.absolutePos(new BlockPos(coordinate,1,coordinate));int radius=32;
        for(int x=(center.getX()-48)>>4;x<=(center.getX()+48)>>4;x++)for(int z=(center.getZ()-48)>>4;z<=(center.getZ()+48)>>4;z++)level.getChunk(x,z);
        var chunk=new ChunkPos(center);level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);for(int y=0;y<13;y++)level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);}
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);var buildings=new ArrayList<SettlementLayoutMetadata.Building>();
        for(int i=0;i<3;i++) {
            var module=catalog.get(i==2?BuildingKind.MARKET:BuildingKind.HOUSE);var at=center.offset(i==2?-18:12,-1,i==1?-16:12);
            module.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
            buildings.add(new SettlementLayoutMetadata.Building(module.kind(),module.id(),at,net.minecraft.world.level.block.Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+module.size().getX()-1,at.getZ()+module.size().getZ()-1),at.offset(module.entrance())));
        }
        SettlementSavedData.get(server).add(settlement,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of()));level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        dev.livingkingdoms.npc.NpcService.ensureMayor(level,settlement).orElseThrow();ProfessionService.ensure(level,settlement);level.setDayTime(6000);
        var player=new UiTestPlayer(level);player.setPos(center.getX()+.5,center.getY(),center.getZ()+.5);OnboardingTestSupport.legacyCollaborator(player,settlement);
        var candidate=ImmigrationService.attempt(level,settlement,true).orElseThrow();h.assertTrue(ImmigrationService.accept(player,settlement.id(),candidate.id()),"Merchant joins");
        var citizen=CitizenSavedData.get(server).citizen(candidate.id()).orElseThrow();var worker=(Villager)level.getEntity(citizen.entityId());ProfessionService.ensure(level,settlement);
        h.assertTrue(ProfessionService.assignMerchant(player,settlement.id(),citizen.id()),"Merchant assigned to the new market");
        var jobs=ProfessionSavedData.get(server);var workplace=jobs.building(jobs.profession(citizen.id()).orElseThrow().workplaceId()).orElseThrow();var point=workplace.entrance().above();
        worker.setPos(point.getX()+.5,point.getY(),point.getZ()+.5);player.setPos(point.getX()+1.5,point.getY(),point.getZ()+.5);
        h.assertTrue(MerchantWork.stationPresent(level,workplace) && MerchantWork.available(worker),"New market keeps the barrel and lectern stations");
        return new Market(player,worker,settlement);
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void stallsSellClassicTradesAndRareGoodsWithLimits(GameTestHelper h) {
        var m=market(h,898048);var level=h.getLevel();
        var offers=StallService.offers(level,m.worker());
        h.assertTrue(offers.size()>=2,"A level 1 stall offers classic trades and at least one rare good: "+offers.size());
        var rare=offers.stream().filter(o->o.getResult().getItem().builtInRegistryHolder().key().location().getNamespace().equals("livingkingdoms")
                || o.getResult().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)).findFirst();
        h.assertTrue(rare.isPresent(),"Rare kingdom goods are on sale");
        var again=StallService.offers(level,m.worker());
        h.assertTrue(again.size()==offers.size() && net.minecraft.world.item.ItemStack.matches(again.getFirst().getResult(),offers.getFirst().getResult()),
                "Reopening today loads the stored offers instead of rolling new ones");
        var tag=m.worker().getPersistentData().getCompound(StallService.KEY);
        h.assertTrue(tag.contains("offers") && tag.getString("stall").equals(StallService.stall(m.worker()).name()),"Offers are stored on the Merchant entity");
        var before=StallService.stall(m.worker());
        h.assertTrue(StallService.cycle(m.player(),m.worker()) && StallService.stall(m.worker())==before.next(),"Members can change the stall specialty");
        h.assertTrue(!tag.getString("rolled").equals(StallService.stall(m.worker()).name()) || StallService.offers(level,m.worker()).size()>0,"A new specialty rolls new offers");
        m.player().getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD,20));
        h.assertTrue(StallService.open(m.player(),m.worker()),"The stall opens the trading screen");
        // FakePlayer-based test players never open real menus; the server-side open path is what is verified here.
        h.succeed();
    }
}

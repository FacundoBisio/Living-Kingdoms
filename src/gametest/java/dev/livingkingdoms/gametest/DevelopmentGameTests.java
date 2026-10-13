package dev.livingkingdoms.gametest;

import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DevelopmentGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_development_test",Comparator.comparingLong(ChunkPos::toLong),600);
    @GameTest(template="empty",timeoutTicks=400)
    public static void newEconomicModulesResolveNativeBlocksInAllRotations(GameTestHelper h) {
        var catalog=BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS);
        h.assertTrue(catalog.modules().size()==14,"All existing and new module identities remain loadable");
        int index=0;
        for(var kind:List.of(BuildingKind.WAREHOUSE,BuildingKind.LUMBERJACK_HUT,BuildingKind.MINE,BuildingKind.MARKET)) {
            var module=catalog.get(kind);
            for(var rotation:Rotation.values()) {
                var at=h.absolutePos(new BlockPos(885696+(index++%4)*20,1,885696+(index/4)*20));
                h.getLevel().getChunk(at);h.getLevel().getChunk(at.east(16).south(16));
                var settings=SettlementTemplate.settings().setRotation(rotation);
                var origin=module.nativeOrigin(at,rotation);
                h.assertTrue(module.template().placeInWorld(h.getLevel(),origin,origin,settings,h.getLevel().random,18),"New module places through native Minecraft codecs");
                var size=module.rotatedSize(rotation);
                for(int x=0;x<size.getX();x++) for(int z=0;z<size.getZ();z++) h.assertTrue(h.getLevel().getBlockState(at.offset(x,0,z)).isSolid(),"Rotated footprint has a complete solid floor");
                if(kind==BuildingKind.MINE) h.assertTrue(module.blocks().stream().noneMatch(b->b.state().is(Blocks.IRON_ORE)),"Economic mines cannot mint harvestable ore decorations");
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void warehouseTownHallFundingAndExpansionHaveRealSavedEffects(GameTestHelper h) {
        var level=h.getLevel();var center=h.absolutePos(new BlockPos(887744,1,887744));int radius=64;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center);level.getChunkSource().addRegionTicket(FIXTURE,chunk,6,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);for(int y=0;y<12;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);}
        var module=BuildingCatalog.load(level,ArchitectureStyle.PLAINS).get(BuildingKind.WAREHOUSE);var at=center.offset(12,-1,12);
        module.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
        var building=new SettlementLayoutMetadata.Building(BuildingKind.WAREHOUSE,module.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+8,at.getZ()+8),at.offset(module.entrance()));
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),2,SettlementOrigin.CONVERTED,UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(building),List.of(center.east().below()),List.of()));level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var player=new UiTestPlayer(level);player.setPos(center.getX()+.5,center.getY(),center.getZ()+.5);OnboardingTestSupport.legacyCollaborator(player,s);ProfessionService.ensure(level,s);fill(player,s);
        var jobs=ProfessionSavedData.get(level.getServer());var warehouse=jobs.buildings(s.id()).stream().filter(b->b.kind()==BuildingKind.WAREHOUSE).findFirst().orElseThrow();
        int capacity=KingdomEconomy.capacity(player.server,s.id());
        h.assertTrue(BuildingDevelopment.upgrade(player,s.id(),warehouse.id()) && KingdomEconomy.capacity(player.server,s.id())>capacity,"Paid Warehouse upgrade increases actual shared storage");
        var hall=jobs.buildings(s.id()).stream().filter(b->b.kind()==BuildingKind.TOWN_HALL).findFirst().orElseThrow();int slots=hall.workplaceSlots();
        h.assertTrue(BuildingDevelopment.upgrade(player,s.id(),hall.id()) && jobs.building(hall.id()).orElseThrow().workplaceSlots()>slots,"Converted administration upgrade increases real Builder slots");
        fill(player,s);
        h.assertTrue(BuildingDevelopment.expand(player,s.id()) && SettlementSavedData.get(player.server).get(s.id()).orElseThrow().territory().radius()==64,"Expansion changes the actual location-indexed territory");
        h.assertTrue(ConstructionService.planEconomicBuilding(level,s.id(),player,BuildingKind.LUMBERJACK_HUT),"New economic building uses existing safe terrain planner");
        var project=ConstructionSavedData.get(player.server).projects(s.id()).stream().filter(e->e.project().building()==BuildingKind.LUMBERJACK_HUT).findFirst().orElseThrow();
        fill(player,s);int wood=KingdomEconomy.count(player.server,s.id(),StockResource.WOOD);
        h.assertTrue(ConstructionService.depositStock(player,s.id(),project.project().id()),"Shared materials fund the real persisted construction project");
        h.assertTrue(ConstructionSavedData.get(player.server).get(project.project().id()).orElseThrow().project().state()==ConstructionState.READY
                && KingdomEconomy.count(player.server,s.id(),StockResource.WOOD)<wood,"Funding spends materials and queues for an actual Builder");
        h.assertTrue(!ConstructionService.depositStock(player,s.id(),project.project().id()) && !BuildingDevelopment.expand(player,s.id()),"Funding cannot repeat; unfinished construction blocks expansion");h.succeed();
    }
    private static void fill(UiTestPlayer player,Settlement settlement) {
        int slot=0;for(var resource:List.of(StockResource.FOOD,StockResource.WOOD,StockResource.STONE,StockResource.IRON)) {
            player.getInventory().setItem(slot++,new ItemStack(resource.item(),64));KingdomEconomy.deposit(player,settlement.id(),resource,64);
        }
    }
}

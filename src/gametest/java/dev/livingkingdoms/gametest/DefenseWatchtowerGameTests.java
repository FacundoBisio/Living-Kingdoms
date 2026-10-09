package dev.livingkingdoms.gametest;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.SettlementSafety;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.encounter.domain.*;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.SecurityService;
import dev.livingkingdoms.profession.domain.BuildingCapability;
import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.UiPayloads;
import dev.livingkingdoms.ui.VillageUiService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DefenseWatchtowerGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_watchtower_test",Comparator.comparingLong(ChunkPos::toLong),1200);
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player,Villager mayor) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeWatchtowerConstructionUsesSharedResourcesAndImprovesSecurityOnce(GameTestHelper h) {
        var f=fixture(h,310000); var server=h.getLevel().getServer();
        int original=SecurityService.score(server,f.settlement().id());
        h.assertTrue(VillageUiService.openDialogue(f.player(),f.mayor()),"Mayor opens ordinary shared UI");
        var token=f.player().snapshots.getLast().getUUID("session");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.CONSTRUCTION)),"Construction view available");
        h.assertTrue(f.player().snapshots.getLast().getBoolean("watchtower_plan"),"Watchtower offered before construction");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.PLAN_WATCHTOWER)),"Native UI plans Watchtower");
        var project=ConstructionService.current(server,f.settlement().id()).orElseThrow().project();
        h.assertTrue(project.building()==BuildingKind.WATCHTOWER && project.durationTicks()>0 && project.required().size()==3,"Watchtower uses configurable logs, stone, iron and timer");
        fund(f);
        h.assertTrue(ConstructionService.deposit(f.player(),f.settlement().id(),project.id()),"Real inventory funds shared construction");
        h.assertTrue(!ConstructionService.resolve(server,project.id(),f.player()),"Normal completion respects timer");
        h.assertTrue(ConstructionService.resolve(server,project.id(),f.player(),true),"Protected native blueprint placed after development time skip");
        h.assertTrue(!ConstructionService.resolve(server,project.id(),f.player(),true),"Completed construction cannot duplicate");
        var tower=tower(f);
        h.assertTrue(tower.active() && tower.capabilities().contains(BuildingCapability.DEFENSE_SUPPORT),"Functional Watchtower registered from placed native building");
        h.assertTrue(SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow().buildings().stream().filter(b -> b.kind()==BuildingKind.WATCHTOWER).count()==1,"One native layout receipt");
        h.assertTrue(SecurityService.score(server,f.settlement().id())>original,"Watchtower improves existing derived Security");
        h.assertTrue(!ConstructionService.planWatchtower(h.getLevel(),f.settlement().id(),f.player()),"Existing Watchtower rejects duplicate plan");
        VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.REFRESH));
        h.assertTrue(!f.player().snapshots.getLast().getBoolean("watchtower_plan"),"Latest shared construction snapshot disables duplicate");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeWatchtowerDetectsRealLoadedPartyBeyondBaselineRangeWithoutChunkLoading(GameTestHelper h) {
        var f=fixture(h,311024); var level=h.getLevel(); var server=level.getServer();
        double baseline=DefenseService.detectionRange(server,f.settlement());
        var at=f.center().east((int)Math.ceil(baseline)+4);
        var enemy=EntityType.PILLAGER.create(level); enemy.setPos(at.getX()+.5,at.getY(),at.getZ()+.5); enemy.setNoAi(true); enemy.setPersistenceRequired();
        var ids=Set.of(enemy.getUUID());
        var party=new HostileParty(UUID.randomUUID(),PartyType.PILLAGER_PATROL.faction(),PartyType.PILLAGER_PATROL,
                new OriginRegion(level.dimension().location().toString(),at.getX(),at.getY(),at.getZ(),8),f.settlement().id(),PartyState.ALIVE,ids,ids,15,2,false,true);
        EncounterSavedData.get(server).add(party,server.overworld().getGameTime()); EncounterMember.attach(enemy,party.id(),party.faction()); level.addFreshEntity(enemy);
        var untouched=new ChunkPos(f.center().offset(4096,0,-4096));
        h.assertTrue(!level.getChunkSource().hasChunk(untouched.x,untouched.z),"Remote control chunk starts unloaded");
        DefenseService.detect(level,party,enemy.blockPosition());
        h.assertTrue(DefenseService.current(server,f.settlement().id()).isEmpty() && DefenseService.status(server,f.settlement().id())==SettlementSafety.SAFE,"Real party lies outside baseline local detection");
        h.assertTrue(ConstructionService.planWatchtower(level,f.settlement().id(),f.player()),"Survival Watchtower plan accepted");
        var project=ConstructionService.current(server,f.settlement().id()).orElseThrow().project(); fund(f);
        h.assertTrue(ConstructionService.deposit(f.player(),f.settlement().id(),project.id()) && ConstructionService.resolve(server,project.id(),f.player(),true),"Real materials and native placement activate Watchtower");
        h.assertTrue(DefenseService.detectionRange(server,f.settlement())>baseline && enemy.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(f.center()))<=Math.pow(DefenseService.detectionRange(server,f.settlement()),2),"Party is within improved Watchtower detection range");
        DefenseService.detect(level,party,enemy.blockPosition());
        var event=DefenseService.current(server,f.settlement().id()).orElseThrow();
        h.assertTrue(event.partyId().equals(party.id()) && event.remainingMembers()==1 && DefenseService.status(server,f.settlement().id())==SettlementSafety.THREATENED,"Actual nearby party becomes settlement-shared Watchtower threat");
        h.assertTrue(!level.getChunkSource().hasChunk(untouched.x,untouched.z),"Detection and native construction leave distant chunks unloaded");
        enemy.hurt(level.damageSources().genericKill(),1000); h.succeed();
    }

    private static void fund(Fixture f) {
        f.player().getInventory().items.set(0,new ItemStack(Items.OAK_LOG,64));
        f.player().getInventory().items.set(1,new ItemStack(Items.STONE,64));
        f.player().getInventory().items.set(2,new ItemStack(Items.IRON_INGOT,64));
    }
    private static FunctionalBuilding tower(Fixture f) {
        return ProfessionSavedData.get(f.player().server).buildings(f.settlement().id()).stream().filter(b -> b.kind()==BuildingKind.WATCHTOWER).findFirst().orElseThrow();
    }
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        // Explicit chunk setup belongs to the test fixture; the production paths below never load chunks.
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
            level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS); var buildings=new ArrayList<SettlementLayoutMetadata.Building>();
        for(int i=0;i<2;i++) {
            var module=catalog.get(BuildingKind.HOUSE); var at=center.offset(12,-1,i==0?12:-16);
            module.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
            buildings.add(new SettlementLayoutMetadata.Building(module.kind(),module.id(),at,Rotation.NONE,
                    new PlotBounds(at.getX(),at.getZ(),at.getX()+module.size().getX()-1,at.getZ()+module.size().getZ()-1),at.offset(module.entrance())));
        }
        SettlementSavedData.get(level.getServer()).add(settlement,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of()));
        level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,settlement).orElseThrow(); ProfessionService.ensure(level,settlement);
        var player=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } };
        player.getAbilities().mayBuild=true; player.setPos(mayor.getX()+2,mayor.getY(),mayor.getZ());
        return new Fixture(center,settlement,player,mayor);
    }
}

package dev.livingkingdoms.gametest;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuilderProfessionGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_builders_test",Comparator.comparingLong(ChunkPos::toLong),1200);
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player) {}
    private record Worker(Citizen citizen,Villager entity) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void builderUsesNativeNavigationContributesWorkAndEarnsFirstBuilderOnce(GameTestHelper h) {
        var f=fixture(h,320000); var w=worker(h,f); var entry=project(h,f,400);
        var at=ConstructionService.workPoint(entry); w.entity().setPos(at.getX()+.5,at.getY(),at.getZ()-11.5); var initial=w.entity().position();
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Normal assignment starts the funded project");
        var assigned=ConstructionSavedData.get(h.getLevel().getServer()).get(entry.project().id()).orElseThrow().project();
        h.assertTrue(assigned.state()==ConstructionState.BUILDING && w.citizen().id().equals(assigned.builderId()),"One authoritative project links the actual citizen");
        BuilderWork.tick(w.entity()); h.assertTrue(initial.equals(w.entity().position()),"Work never teleports the villager");
        var advancement=h.getLevel().getServer().getAdvancements().get(KingdomMilestone.FIRST_BUILDER.id());
        h.assertTrue(advancement!=null && f.player().getAdvancements().getOrStartProgress(advancement).isDone()
                && !KingdomMilestone.awardFirstBuilder(f.player()),"Native player advancement is granted once");
        var sawPath=new java.util.concurrent.atomic.AtomicBoolean();
        h.succeedWhen(() -> {
            if(!w.entity().getNavigation().isDone()) sawPath.set(true);
            var p=ConstructionSavedData.get(h.getLevel().getServer()).get(entry.project().id()).orElseThrow().project();
            h.assertTrue(p.workTicks()>0,"Builder travels and credits physical work; state="+ProfessionSavedData.get(h.getLevel().getServer()).profession(w.citizen().id()).orElseThrow().workState()+", position="+w.entity().position());
            h.assertTrue(sawPath.get() && initial.distanceToSqr(w.entity().position())>4,"A real native path brought the Builder to the site");
            h.assertTrue(w.entity().distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)<=16,"Credited work requires physical proximity");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void directlyAttackedBuilderPausesAndPreservesNativeCareerTradesAndHome(GameTestHelper h) {
        var f=fixture(h,321024); var w=worker(h,f); var entry=project(h,f,400); var level=h.getLevel();
        w.entity().setVillagerData(w.entity().getVillagerData().setProfession(VillagerProfession.FARMER).setLevel(2)); w.entity().setVillagerXp(20);
        w.entity().getOffers();
        var trades=w.entity().saveWithoutId(new CompoundTag()).getCompound("Offers").copy(); var career=w.entity().getVillagerData(); var home=w.citizen().homeId();
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Existing career needs explicit Builder assignment");
        var brain=w.entity().getBrain(); var at=ConstructionService.workPoint(entry); w.entity().setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        var prior=ConstructionSavedData.get(level.getServer()).get(entry.project().id()).orElseThrow().project();
        h.assertTrue(w.entity().hurt(level.damageSources().generic(),1) && BuilderWork.inDanger(w.entity()),"Real direct damage enters danger state");
        h.assertTrue(!BuilderWork.tick(w.entity()) && w.entity().getBrain()!=brain,"Panic releases the controlled goal and restores native survival Brain");
        h.assertTrue(ConstructionSavedData.get(level.getServer()).get(entry.project().id()).orElseThrow().project().workTicks()==prior.workTicks(),"Danger credits no work");
        h.assertTrue(w.entity().getVillagerData().equals(career) && w.entity().getVillagerXp()==20
                && trades.equals(w.entity().saveWithoutId(new CompoundTag()).getCompound("Offers"))
                && home.equals(CitizenSavedData.get(level.getServer()).citizen(w.citizen().id()).orElseThrow().homeId()),"Assignment and panic preserve native trades/career and persisted home");
        var unrelated=EntityType.VILLAGER.create(level); unrelated.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        h.assertTrue(!BuilderWork.tick(unrelated),"Unregistered vanilla villagers cannot credit construction");
        h.assertTrue(ProfessionService.remove(f.player(),f.settlement().id(),w.citizen().id()),"Mayor can remove the Builder safely");
        var ready=ConstructionSavedData.get(level.getServer()).get(entry.project().id()).orElseThrow().project();
        h.assertTrue(ready.state()==ConstructionState.READY && ready.builderId()==null && ready.supplied().equals(prior.supplied()),"Removal retains all funded project data"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void builderDeathLeavesFundedProgressAndAllowsAnotherCitizenToContinue(GameTestHelper h) {
        var f=fixture(h,322048); var first=worker(h,f); var replacement=worker(h,f); var entry=project(h,f,400); var server=h.getLevel().getServer();
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),first.citizen().id()),"First Builder takes the project");
        var storage=ConstructionSavedData.get(server); var p=storage.get(entry.project().id()).orElseThrow().project(); storage.replace(p,p.addWork(ConstructionService.now(server)+1,75));
        var progress=storage.get(p.id()).orElseThrow().project(); first.entity().hurt(h.getLevel().damageSources().genericKill(),1000);
        var ready=storage.get(p.id()).orElseThrow().project();
        h.assertTrue(ready.state()==ConstructionState.READY && ready.builderId()==null && ready.workTicks()==75
                && ready.visualStage()==progress.visualStage() && ready.supplied().equals(progress.supplied()) && ready.plot().equals(progress.plot()),"Confirmed death preserves materials, stage, plot and accumulated work");
        h.assertTrue(CitizenSavedData.get(server).citizen(first.citizen().id()).orElseThrow().state()==CitizenState.DEAD
                && !ProfessionSavedData.get(server).profession(first.citizen().id()).orElseThrow().active(),"Death frees citizen/profession occupancy without respawning");
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),replacement.citizen().id()),"Replacement Builder resumes normally");
        var resumed=storage.get(p.id()).orElseThrow().project();
        h.assertTrue(resumed.state()==ConstructionState.BUILDING && replacement.citizen().id().equals(resumed.builderId()) && resumed.workTicks()==75,"Replacement owns the same persisted project with no restart"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void builderEntityUnloadAndRejoinRestoresAssignmentWithoutOfflineWork(GameTestHelper h) {
        var f=fixture(h,323072); var w=worker(h,f); var entry=project(h,f,400); var level=h.getLevel(); var server=level.getServer();
        ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()); var storage=ConstructionSavedData.get(server);
        var before=storage.get(entry.project().id()).orElseThrow().project(); var saved=w.entity().saveWithoutId(new CompoundTag());
        w.entity().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        h.assertTrue(storage.get(before.id()).orElseThrow().project().equals(before),"Entity unload keeps assignment and pauses work");
        var loadedProfiles=ProfessionSavedData.load(ProfessionSavedData.get(server).save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(loadedProfiles.profession(w.citizen().id()).equals(ProfessionSavedData.get(server).profession(w.citizen().id())),"Saved profession restores workplace and work schedule");
        h.runAfterDelay(40,() -> {
            h.assertTrue(storage.get(before.id()).orElseThrow().project().workTicks()==before.workTicks(),"An absent unloaded Builder receives no offline construction credit");
            var reloaded=EntityType.VILLAGER.create(level); reloaded.load(saved); h.assertTrue(level.addFreshEntity(reloaded),"Native saved entity returns with its existing UUID");
            h.assertTrue(BuilderWork.assigned(reloaded) && reloaded.getUUID().equals(w.citizen().entityId())
                    && storage.get(before.id()).orElseThrow().project().builderId().equals(w.citizen().id()),"Existing identity resumes its exclusive assignment");
            ProfessionSavedData.get(server).retire(w.citizen().id()); BuilderWork.tick(reloaded);
            h.assertTrue(storage.get(before.id()).orElseThrow().project().state()==ConstructionState.READY
                    && storage.get(before.id()).orElseThrow().project().workTicks()==before.workTicks(),"A previously retired profession releases its project without losing progress");
            h.assertTrue(ProfessionService.remove(f.player(),f.settlement().id(),w.citizen().id())
                    && storage.get(before.id()).orElseThrow().project().state()==ConstructionState.READY,"Reloaded worker can release the same project safely"); h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void convertedResidentUsesActualNativeBedToBuildFirstHouseAndKeepsItAfterReload(GameTestHelper h) {
        var f=fixture(h,324096,false); var level=h.getLevel(); var server=level.getServer(); var w=nativeResident(h,f);
        var head=f.center().offset(-8,0,-6); nativeBed(level,head); w.entity().getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),head));
        var people=CitizenSavedData.get(server); var jobs=ProfessionSavedData.get(server); var beforeHead=level.getBlockState(head);
        h.assertTrue(people.summary(f.settlement().id()).total()==0 && w.citizen().homeId()==null,"Conversion does not automatically invent bed housing");
        h.assertTrue(ProfessionService.canAssignBuilder(level,w.citizen(),jobs.profession(w.citizen().id()).orElseThrow(),jobs.buildings(f.settlement().id()),jobs)
                && people.summary(f.settlement().id()).total()==0,"Read-only UI eligibility recognizes the real bed without changing housing");
        var entry=project(h,f,200,BuildingKind.HOUSE);
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Existing converted resident can become Builder using a verified native home");
        var citizen=people.citizen(w.citizen().id()).orElseThrow(); var home=people.housing(citizen.homeId()).orElseThrow();
        h.assertTrue(home.convertedBed() && home.position().equals(head) && home.capacity()==1 && people.summary(f.settlement().id()).occupied()==1,"One actual bed supplies one explicitly occupied home");
        h.assertTrue(ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project().builderRequired(),"First converted House still uses normal physical Builder work");
        var at=ConstructionService.workPoint(entry); w.entity().setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        h.succeedWhen(() -> {
            var p=ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project();
            h.assertTrue(p.state()==ConstructionState.COMPLETED,"Real Builder work completes the first House; state="+p.state()+", work="+p.workTicks()+"/"+p.durationTicks()+", stage="+p.visualStage()+", position="+w.entity().position()+", workPoint="+ConstructionService.workPoint(entry));
            h.assertTrue(level.getBlockState(head).equals(beforeHead) && people.citizen(citizen.id()).orElseThrow().homeId().equals(home.id()),"New construction preserves the original vanilla bed and its assigned citizen");
            var reopened=CitizenSavedData.load(people.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            reopened.synchronizeHousing(f.settlement(),SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow(),b -> dev.livingkingdoms.config.CitizenConfig.DEFAULT_HOUSE_CAPACITY.get());
            h.assertTrue(reopened.housing(home.id()).orElseThrow().equals(home) && reopened.citizen(citizen.id()).orElseThrow().homeId().equals(home.id()),"Native bed identity survives save/reload and completed-building housing synchronization");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void breakingConvertedBuildersActualBedReleasesProjectWithoutLosingItsMaterials(GameTestHelper h) {
        var f=fixture(h,325120,false); var level=h.getLevel(); var server=level.getServer(); var w=nativeResident(h,f);
        var head=f.center().offset(-8,0,-6); nativeBed(level,head); w.entity().getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),head));
        var entry=project(h,f,200,BuildingKind.HOUSE); h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Real native bed qualifies the first Builder");
        var people=CitizenSavedData.get(server); var home=people.citizen(w.citizen().id()).orElseThrow().homeId();
        var prior=ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project(); level.setBlock(head,Blocks.AIR.defaultBlockState(),18); BuilderWork.tick(w.entity());
        var after=ConstructionSavedData.get(server).get(prior.id()).orElseThrow().project();
        h.assertTrue(after.state()==ConstructionState.READY && after.builderId()==null && after.workTicks()==prior.workTicks()
                && after.visualStage()==prior.visualStage() && after.supplied().equals(prior.supplied()),"Invalid loaded home releases the same funded construction receipt safely");
        h.assertTrue(people.housing(home).orElseThrow().status()==HousingStatus.DISABLED && people.citizen(w.citizen().id()).orElseThrow().homeId()==null
                && !ProfessionSavedData.get(server).profession(w.citizen().id()).orElseThrow().active() && people.summary(f.settlement().id()).free()==0,"Broken native bed retires work and cannot become a spare unverified immigration slot");
        h.assertTrue(!ConstructionService.resolve(server,prior.id(),f.player()),"No eligible Builder can complete this project normally"); h.succeed();
    }
    private static Worker nativeResident(GameTestHelper h,Fixture f) {
        var level=h.getLevel(); var v=EntityType.VILLAGER.create(level); v.moveTo(f.center().getX()-7.5,f.center().getY(),f.center().getZ()-5.5,0,0);
        var c=new Citizen(UUID.randomUUID(),v.getUUID(),f.settlement().id(),"Rowan",new dev.livingkingdoms.progression.domain.LevelValue(1),CitizenRole.UNASSIGNED,null,CitizenState.ACTIVE,ConstructionService.now(level.getServer()));
        h.assertTrue(CitizenSavedData.get(level.getServer()).register(c),"Existing converted citizen is registered without inferred housing");
        CitizenService.apply(c,v); h.assertTrue(level.addFreshEntity(v),"Original vanilla identity joins normally"); ProfessionService.ensure(level,f.settlement()); return new Worker(c,v);
    }
    private static void nativeBed(ServerLevel level,BlockPos head) {
        var state=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH);
        level.setBlock(head.south(),state.setValue(BedBlock.PART,BedPart.FOOT),18); level.setBlock(head,state.setValue(BedBlock.PART,BedPart.HEAD),18);
    }

    private static ConstructionSavedData.Entry project(GameTestHelper h,Fixture f,long duration) {
        return project(h,f,duration,BuildingKind.FARM);
    }
    private static ConstructionSavedData.Entry project(GameTestHelper h,Fixture f,long duration,BuildingKind kind) {
        var plan=new SettlementGenerator().planBuildingAddition(h.getLevel(),f.settlement().id(),kind,new GenerationDiagnostics()).orElseThrow();
        var entry=ConstructionService.reserve(h.getLevel(),f.settlement().id(),plan,Map.of(ResourceKind.LOGS,1),duration,f.player());
        f.player().getInventory().items.set(0,new ItemStack(Items.OAK_LOG));
        h.assertTrue(ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id()),"Survival materials fund the project");
        h.assertTrue(ConstructionSavedData.get(h.getLevel().getServer()).get(entry.project().id()).orElseThrow().project().state()==ConstructionState.READY,"No Builder means READY"); return entry;
    }
    private static Worker worker(GameTestHelper h,Fixture f) {
        var candidate=ImmigrationService.attempt(h.getLevel(),f.settlement(),true).orElseThrow(); h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Citizen accepted into existing house");
        var c=CitizenSavedData.get(h.getLevel().getServer()).citizen(candidate.id()).orElseThrow(); ProfessionService.ensure(h.getLevel(),f.settlement()); return new Worker(c,(Villager)h.getLevel().getEntity(c.entityId()));
    }
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        return fixture(h,coordinate,true);
    }
    private static Fixture fixture(GameTestHelper h,int coordinate,boolean housing) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
            level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var houses=new ArrayList<SettlementLayoutMetadata.Building>(); var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        for(int i=0;i<(housing?2:0);i++) {
            var m=catalog.get(BuildingKind.HOUSE); var at=center.offset(12,-1,i==0?12:-16);
            m.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
            houses.add(new SettlementLayoutMetadata.Building(m.kind(),m.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+m.size().getX()-1,at.getZ()+m.size().getZ()-1),at.offset(m.entrance())));
        }
        var settlements=SettlementSavedData.get(level.getServer()); settlements.add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,houses,List.of(center.east().below()),List.of()));
        level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18); var mayor=NpcService.ensureMayor(level,s).orElseThrow(); ProfessionService.ensure(level,s);
        var player=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } }; player.getAbilities().mayBuild=true;
        player.setPos(mayor.getX()+2,mayor.getY(),mayor.getZ()); return new Fixture(center,s,player);
    }
}

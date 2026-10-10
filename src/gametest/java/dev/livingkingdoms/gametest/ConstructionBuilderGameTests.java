package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.*;
import dev.livingkingdoms.construction.*;
import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.settlement.*;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionBuilderGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_builder_queue_test",Comparator.comparingLong(ChunkPos::toLong),1200);
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player) {}
    private record Worker(Citizen citizen,Villager entity) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void sharedFifoQueueCompletesOnceAwardsMilestonesOnceAndStartsNextProject(GameTestHelper h) {
        var f=fixture(h,340000); var w=worker(h,f); var first=project(h,f,BuildingKind.FARM,80);
        var second=project(h,f,BuildingKind.HOUSE,1600); var server=h.getLevel().getServer(); var storage=ConstructionSavedData.get(server);
        h.assertTrue(storage.readyProjects(f.settlement().id()).size()==2 && storage.buildingCount(f.settlement().id())==0,"Two funded projects share the queue without a Builder");
        standAt(w.entity(),first);
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Normal citizen assignment consumes one administrative slot");
        h.assertTrue(storage.buildingCount(f.settlement().id())==1 && storage.get(first.project().id()).orElseThrow().project().state()==ConstructionState.BUILDING
                && storage.get(second.project().id()).orElseThrow().project().state()==ConstructionState.READY,"Default active limit is one, with deterministic FIFO selection");
        var stale=storage.get(first.project().id()).orElseThrow().project();
        h.succeedWhen(() -> {
            var completed=storage.get(first.project().id()).orElseThrow().project();
            var diagnosticCareer=ProfessionSavedData.get(server).profession(w.citizen().id()).orElseThrow();
            h.assertTrue(completed.state()==ConstructionState.COMPLETED,"Real physical Builder work reaches final placement: state="+completed.state()
                    +", work="+completed.workTicks()+"/"+completed.durationTicks()+", stage="+completed.visualStage()+", awaitingChunks="+completed.awaitingChunks()
                    +", career="+diagnosticCareer.workState()+", position="+w.entity().position()+", workPoint="+ConstructionService.workPoint(first));
            h.assertTrue(!storage.replace(stale,stale.addWork(ConstructionService.now(server),20)),"A stale multiplayer receipt cannot add work after completion");
            h.assertTrue(!ConstructionService.resolve(server,completed.id(),f.player()) && !ConstructionService.resolve(server,completed.id(),f.player(),true),"Neither normal nor operator replays duplicate final placement");
            var metadata=SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow();
            h.assertTrue(metadata.buildings().stream().filter(b -> b.origin().equals(first.plan().buildings().getFirst().origin())).count()==1,"Final building registration occurs once");
            var career=ProfessionSavedData.get(server).profession(w.citizen().id()).orElseThrow();
            long expected=3L*BuilderConfig.XP_PER_STAGE.get()+BuilderConfig.XP_COMPLETION.get();
            h.assertTrue(career.experience()==expected && completed.stageXpAwarded(1) && completed.stageXpAwarded(2)
                    && completed.stageXpAwarded(3) && completed.stageXpAwarded(4),"Only successful stages and one final completion award profession XP");
            var next=storage.get(second.project().id()).orElseThrow().project();
            h.assertTrue(next.state()==ConstructionState.BUILDING && w.citizen().id().equals(next.builderId())
                    && storage.buildingCount(f.settlement().id())==1 && storage.forBuilder(w.citizen().id()).orElseThrow().project().id().equals(next.id()),"Completion releases exclusive ownership and starts the next queued project");
            long before=next.workTicks(); ConstructionService.contribute(w.entity()); ConstructionService.contribute(w.entity());
            h.assertTrue(storage.get(next.id()).orElseThrow().project().workTicks()==before,"Immediate duplicate work calls cannot bank an interval on the next project");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeMidStageAndDiskReloadPreserveExactProgressAssignmentAndMilestoneReceipts(GameTestHelper h) {
        var f=fixture(h,341024); var w=worker(h,f); var entry=project(h,f,BuildingKind.BARRACKS,400); var level=h.getLevel(); var server=level.getServer();
        standAt(w.entity(),entry); h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Builder starts Barracks normally");
        var checkpoint=new AtomicReference<ConstructionProject>(); var rejoined=new AtomicReference<Villager>();
        h.startSequence().thenWaitUntil(() -> {
            var p=ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project();
            h.assertTrue(p.visualStage()>=1 && p.state()==ConstructionState.BUILDING,"Reach a real saved intermediate milestone");
        }).thenExecute(() -> {
            var p=ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project(); checkpoint.set(p);
            try {
                var directory=java.nio.file.Files.createTempDirectory(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),"builder-reload-test-");
                var disk=new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(),server.getFixerUpper(),level.registryAccess());
                var receipts=ConstructionSavedData.getOrCreate(disk,directory); receipts.reserve(p,entry.plan()); disk.save();
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                var reopened=ConstructionSavedData.getOrCreate(new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(),server.getFixerUpper(),level.registryAccess()),directory);
                var restored=reopened.get(p.id()).orElseThrow();
                h.assertTrue(restored.project().equals(p) && reopened.forBuilder(w.citizen().id()).orElseThrow().project().equals(p)
                        && reopened.buildingCount(f.settlement().id())==1 && reopened.ready(Long.MAX_VALUE,16).isEmpty(),"Actual disk reload preserves stage, work, materials, exclusive Builder and ignores legacy clocks");
                h.assertTrue(restored.plan().before().equals(entry.plan().before()) && restored.plan().buildings().getFirst().rotation()==entry.plan().buildings().getFirst().rotation(),"Saved transformed plot and preconditions remain exact");
            } catch(java.io.IOException e) { throw new IllegalStateException(e); }
            var tag=w.entity().saveWithoutId(new CompoundTag()); w.entity().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(ConstructionSavedData.get(server).get(p.id()).orElseThrow().project().equals(p),"Native entity unload keeps the construction receipt unchanged");
            var nativeEntity=EntityType.VILLAGER.create(level); nativeEntity.load(tag); rejoined.set(nativeEntity);
            h.assertTrue(level.addFreshEntity(nativeEntity) && BuilderWork.assigned(nativeEntity),"Existing UUID and Builder profile rejoin without a new assignment");
        }).thenIdle(60).thenExecute(() -> {
            var p=ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project();
            h.assertTrue(p.workTicks()>checkpoint.get().workTicks() && p.visualStage()>=checkpoint.get().visualStage()
                    && p.builderId().equals(w.citizen().id()) && (p.awardedStages() & checkpoint.get().awardedStages())==checkpoint.get().awardedStages(),"Loaded Builder continues from persisted work without resetting or replaying milestone XP");
            h.assertTrue(rejoined.get().getUUID().equals(w.citizen().entityId()),"Entity identity survives the native save/load path");
        }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void unloadedBlueprintKeepsWorkAndReceiptsUntilNativeWorkerAndChunksReturn(GameTestHelper h) {
        var f=fixture(h,342048); var source=project(h,f,BuildingKind.WATCHTOWER,400); var level=h.getLevel(); var server=level.getServer();
        var destination=h.absolutePos(new BlockPos(2_300_000,0,2_300_000)); int dx=destination.getX()-f.center().getX(), dz=destination.getZ()-f.center().getZ();
        var t=f.settlement().territory(); var remoteTerritory=new Territory(t.dimension(),t.x()+dx,t.y(),t.z()+dz,t.radius());
        var remote=Settlement.established(UUID.randomUUID(),remoteTerritory,1,SettlementOrigin.CONVERTED,f.player().getUUID());
        var metadata=SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow();
        var remoteLayout=new SettlementLayoutMetadata(metadata.style(),metadata.buildings().stream().map(b -> new SettlementLayoutMetadata.Building(b.kind(),b.template(),b.origin().offset(dx,0,dz),b.rotation(),shift(b.bounds(),dx,dz),b.entrance().offset(dx,0,dz))).toList(),metadata.ports().stream().map(p -> p.offset(dx,0,dz)).toList(),List.of());
        SettlementSavedData.get(server).add(remote,remoteLayout); var people=CitizenSavedData.get(server);
        people.synchronizeHousing(remote,remoteLayout,building -> 2); people.markInitialized(remote.id());
        var nativeWorker=EntityType.VILLAGER.create(level); var home=people.houses(remote.id()).getFirst().id();
        var citizen=new Citizen(UUID.randomUUID(),nativeWorker.getUUID(),remote.id(),"Rowan",new LevelValue(1),CitizenRole.UNASSIGNED,home,CitizenState.ACTIVE,ConstructionService.now(server));
        h.assertTrue(people.register(citizen),"Unloaded remote citizen retains a real reserved home");
        var jobs=ProfessionSavedData.get(server); jobs.synchronize(remote,remoteLayout,2,4,2,people.citizens(remote.id()));
        var workplace=jobs.buildings(remote.id()).stream().filter(b -> b.supports(ProfessionType.BUILDER)).findFirst().orElseThrow();
        h.assertTrue(jobs.assignBuilder(citizen,workplace.id(),ConstructionService.now(server)),"Persisted Builder can exist while its native entity is unloaded");
        var b=source.plan().buildings().getFirst(); var plan=new SettlementLayout(remoteTerritory,source.plan().style(),List.of(new SettlementLayout.Building(b.module(),b.origin().offset(dx,0,dz),b.rotation(),shift(b.bounds(),dx,dz),b.entrance().offset(dx,0,dz),b.supports().stream().map(p -> p.offset(dx,0,dz)).toList())),shift(source.plan().pathBlocks(),dx,dz),shift(source.plan().before(),dx,dz));
        long now=ConstructionService.now(server); var plot=source.project().plot();
        var p=ConstructionProject.planned(remote.id(),BuildingKind.WATCHTOWER,new ConstructionProject.Plot(plot.x()+dx,plot.y(),plot.z()+dz,plot.rotation()),source.project().required(),400,now).requiringBuilder().waitForResources();
        p=p.supply(p.required()).start(now,citizen.id()).addWork(now+1,100); var storage=ConstructionSavedData.get(server); storage.reserve(p,plan); final var receipt=p;
        h.assertTrue(!level.getChunkSource().hasChunk(p.plot().x()>>4,p.plot().z()>>4),"Remote construction chunk starts unloaded");
        try(var stage=ConstructionStages.apply(level,storage.get(p.id()).orElseThrow(),-1,0,null)) { h.assertTrue(stage.result()==ConstructionStages.Result.UNLOADED,"Stage validation pauses before reading or loading chunks"); }
        h.assertTrue(!ConstructionService.resolve(server,p.id(),null,true) && storage.get(p.id()).orElseThrow().project().equals(p),"Even operator completion preserves work when the site is unloaded");
        var isolated=new ConstructionSavedData(); isolated.reserve(p,plan); var reloaded=ConstructionSavedData.load(isolated.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(reloaded.get(p.id()).orElseThrow().project().equals(p) && reloaded.forBuilder(citizen.id()).isPresent()
                && reloaded.ready(Long.MAX_VALUE,8).isEmpty() && !level.getChunkSource().hasChunk(p.plot().x()>>4,p.plot().z()>>4),"Reload retains work/assignment and creates no offline deadline or chunk load");
        h.runAfterDelay(40,() -> {
            h.assertTrue(storage.get(receipt.id()).orElseThrow().project().equals(receipt),"Absent native worker receives no offline work");
            // Explicit test-only chunk loading restores the recorded terrain; production never does this.
            for(var pos:plan.before().keySet()) level.getChunk(pos.getX()>>4,pos.getZ()>>4);
            for(var state:plan.before().entrySet()) level.setBlock(state.getKey(),state.getValue(),18);
            level.setBlock(storage.get(receipt.id()).orElseThrow().marker(),KingdomBlocks.CONSTRUCTION_MARKER.get().defaultBlockState(),18);
            var chunk=new ChunkPos(storage.get(receipt.id()).orElseThrow().marker()); level.getChunkSource().addRegionTicket(FIXTURE,chunk,4,chunk);
            var at=ConstructionService.workPoint(storage.get(receipt.id()).orElseThrow());
            nativeWorker.setNoAi(true); nativeWorker.setPos(at.getX()+.5,at.getY(),at.getZ()+.5); CitizenService.apply(citizen,nativeWorker); h.assertTrue(level.addFreshEntity(nativeWorker),"Persisted native Builder returns to its saved site");
            h.startSequence().thenWaitUntil(() -> {
                h.assertTrue(level.isPositionEntityTicking(at) && level.areEntitiesLoaded(new ChunkPos(at).toLong())
                        && level.getEntity(nativeWorker.getUUID())==nativeWorker
                        && ConstructionService.isLoaded(server,storage.get(receipt.id()).orElseThrow()),
                        "Reload fixture waits for native entity tracking and all saved project chunks");
            }).thenExecute(() -> {
                nativeWorker.setNoAi(false);
                h.assertTrue(ConstructionService.contribute(nativeWorker),"Loaded and physically present Builder resumes a single work interval; position="
                        +nativeWorker.position()+", point="+at+", profile="+BuilderWork.profile(nativeWorker)+", project="+storage.get(receipt.id()).orElseThrow().project());
                var resumed=storage.get(receipt.id()).orElseThrow().project();
                h.assertTrue(resumed.workTicks()>receipt.workTicks() && resumed.workTicks()<=receipt.workTicks()+25
                        && resumed.visualStage()==1 && resumed.builderId().equals(citizen.id()),"Resume keeps prior work, places the due stage and never banks unloaded time");
                h.assertTrue(!ConstructionService.contribute(nativeWorker) && storage.get(receipt.id()).orElseThrow().project().equals(resumed),"Same-tick duplicate contribution cannot progress twice");
            }).thenSucceed();
        });
    }

    private static ConstructionSavedData.Entry project(GameTestHelper h,Fixture f,BuildingKind kind,long duration) {
        var plan=new SettlementGenerator().planBuildingAddition(h.getLevel(),f.settlement().id(),kind,new GenerationDiagnostics()).orElseThrow();
        var entry=ConstructionService.reserve(h.getLevel(),f.settlement().id(),plan,Map.of(ResourceKind.LOGS,1),duration,f.player());
        f.player().getInventory().clearContent(); f.player().getInventory().items.set(0,new ItemStack(Items.OAK_LOG));
        h.assertTrue(ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id()),"Shared material delivery funds the reserved plot"); return entry;
    }
    private static void standAt(Villager v,ConstructionSavedData.Entry entry) { var at=ConstructionService.workPoint(entry); v.setPos(at.getX()+.5,at.getY(),at.getZ()+.5); }
    private static Worker worker(GameTestHelper h,Fixture f) {
        var candidate=ImmigrationService.attempt(h.getLevel(),f.settlement(),true).orElseThrow(); h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Citizen accepted into an existing House");
        var c=CitizenSavedData.get(h.getLevel().getServer()).citizen(candidate.id()).orElseThrow(); ProfessionService.ensure(h.getLevel(),f.settlement()); return new Worker(c,(Villager)h.getLevel().getEntity(c.entityId()));
    }
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) { level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18); for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18); }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var houses=new ArrayList<SettlementLayoutMetadata.Building>(); var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        for(int i=0;i<2;i++) { var m=catalog.get(BuildingKind.HOUSE); var at=center.offset(12,-1,i==0?12:-16); m.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18); houses.add(new SettlementLayoutMetadata.Building(m.kind(),m.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+m.size().getX()-1,at.getZ()+m.size().getZ()-1),at.offset(m.entrance()))); }
        SettlementSavedData.get(level.getServer()).add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,houses,List.of(center.east().below()),List.of())); level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,s).orElseThrow(); mayor.setNoAi(true); ProfessionService.ensure(level,s);
        var player=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } }; player.getAbilities().mayBuild=true; player.setPos(mayor.getX()+2,mayor.getY(),mayor.getZ()); return new Fixture(center,s,player);
    }
    private static PlotBounds shift(PlotBounds b,int dx,int dz) { return new PlotBounds(b.minX()+dx,b.minZ()+dz,b.maxX()+dx,b.maxZ()+dz); }
    private static Map<BlockPos,net.minecraft.world.level.block.state.BlockState> shift(Map<BlockPos,net.minecraft.world.level.block.state.BlockState> map,int dx,int dz) { var result=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>(); map.forEach((p,s) -> result.put(p.offset(dx,0,dz),s)); return result; }
}

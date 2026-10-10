package dev.livingkingdoms.gametest;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.ConstructionConfig;
import dev.livingkingdoms.construction.*;
import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.npc.*;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.*;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_construction_test",Comparator.comparingLong(ChunkPos::toLong),600);
    @GameTest(template="empty",timeoutTicks=600)
    public static void charterCreatesOnlyCampAndFirstSharedProject(GameTestHelper h) {
        var level=h.getLevel(); var f=found(h,140000); var settlement=f.settlement();
        var data=SettlementSavedData.get(level.getServer()); var metadata=data.layout(settlement.id()).orElseThrow();
        h.assertTrue(settlement.lifecycle()==SettlementLifecycle.FOUNDING && settlement.provenance().origin()==SettlementOrigin.FOUNDED
                && settlement.provenance().founder().orElseThrow().equals(f.player().getUUID()),"New Charter foundation stores lifecycle and founder immediately");
        h.assertTrue(metadata.buildings().size()==1 && metadata.buildings().getFirst().kind()==BuildingKind.FOUNDING_CAMP
                && metadata.buildings().getFirst().bounds().maxX()-metadata.buildings().getFirst().bounds().minX()==8,"Only the small camp exists, with no permanent buildings");
        var entry=ConstructionService.current(level.getServer(),settlement.id()).orElseThrow();
        h.assertTrue(entry.project().building()==BuildingKind.TOWN_HALL && entry.project().state()==ConstructionState.WAITING_FOR_RESOURCES
                && entry.project().supplied().isEmpty() && level.getBlockState(entry.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER),"Town Hall is a reserved, unfunded visible project");
        h.assertTrue(level.getBlockState(entry.plan().buildings().getFirst().origin()).isAir(),"Town Hall floor was not placed");
        var mayor=NpcService.ensureMayor(level,settlement).orElseThrow();
        for(int i=0;i<2;i++) h.assertTrue(level.getEntity(mayor.getPersistentData().getUUID("livingkingdoms:founding_resident_"+i)) instanceof Villager,"Two starting residents preserved");
        f.player().setPos(mayor.getX()+2,mayor.getY(),mayor.getZ());
        h.assertTrue(VillageUiService.openDialogue(f.player(),mayor),"Founding Mayor opens existing UI");
        var dialogue=f.player().snapshots.getLast(); h.assertTrue(dialogue.getString("dialogue").equals("ui.livingkingdoms.dialogue.founding"),"Contextual localized founding dialogue");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(dialogue.getUUID("session"),new UUID(0,0),UiPayloads.Action.CONSTRUCTION)),"Mayor opens supply screen");
        h.assertTrue(f.player().snapshots.getLast().getString("screen").equals("construction"),"Native construction snapshot delivered");
        var advancement=level.getServer().getAdvancements().get(KingdomMilestone.FIRST_KINGDOM.id());
        h.assertTrue(advancement!=null && f.player().getAdvancements().getOrStartProgress(advancement).isDone()
                && !KingdomMilestone.awardFirstKingdom(f.player()),"First kingdom advancement is awarded only once");
        f.player().getAdvancements().save();
        var advancementFile=serverPath(level).resolve("advancements").resolve(f.player().getUUID()+".json");
        var reopenedAdvancements=new net.minecraft.server.PlayerAdvancements(level.getServer().getFixerUpper(),level.getServer().getPlayerList(),
                level.getServer().getAdvancements(),advancementFile,f.player());
        h.assertTrue(reopenedAdvancements.getOrStartProgress(advancement).isDone() && !reopenedAdvancements.award(advancement,"established"),"Native advancement file retains one-time receipt after reopen");
        reopenedAdvancements.stopListening();
        ExpandedQuestService.prepare(f.player(),settlement);
        h.assertTrue(QuestSavedData.get(level.getServer()).quests(f.player().getUUID(),settlement.id()).stream().allMatch(q -> q.template()==QuestTemplate.FIRST_MEETING
                || q.template()==QuestTemplate.FOOD_REQUEST || q.template()==QuestTemplate.BUILDING_REQUEST),"Founding avoids smith/combat quest assumptions");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void multiplayerPartialDepositsConsumeExactlyMissingMaterials(GameTestHelper h) {
        var f=found(h,141024); var server=h.getLevel().getServer(); var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        var first=f.player(); first.getInventory().clearContent(); first.getInventory().items.set(0,new ItemStack(Items.OAK_LOG,20));
        h.assertTrue(ConstructionService.deposit(first,f.settlement().id(),entry.project().id()),"First player contributes a partial tagged log delivery");
        h.assertTrue(!ConstructionService.deposit(first,f.settlement().id(),entry.project().id()) && DeliveryInventory.count(first.getInventory(),ResourceKind.LOGS)==0,"Repeated delivery with no new materials cannot consume twice");
        var second=player(h.getLevel(),first.blockPosition()); supply(second,entry.project().required(),10);
        h.assertTrue(ConstructionService.deposit(second,f.settlement().id(),entry.project().id()),"A different player can fund the same settlement project");
        var built=ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project();
        h.assertTrue(built.state()==ConstructionState.BUILDING && built.supplied().equals(built.required()),"Shared receipt reaches building exactly once");
        h.assertTrue(DeliveryInventory.count(second.getInventory(),ResourceKind.LOGS)==30 && DeliveryInventory.count(second.getInventory(),ResourceKind.STONE)==10
                && DeliveryInventory.count(second.getInventory(),ResourceKind.IRON_INGOT)==10,"Only missing amounts taken; extra materials retained");
        h.assertTrue(!ConstructionService.deposit(second,f.settlement().id(),built.id()) && DeliveryInventory.count(second.getInventory(),ResourceKind.LOGS)==30,"Funded project rejects repeat spending");
        h.assertTrue(!ConstructionService.resolve(server,built.id(),second) && h.getLevel().getBlockState(entry.plan().buildings().getFirst().origin()).isAir(),"No instant same-tick construction");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void deadlinePlacesTownHallThenHouseAndRegistersEstablished(GameTestHelper h) {
        long original=ConstructionConfig.TEST_DURATION.get(); ConstructionConfig.TEST_DURATION.set(40);
        final Founded f;
        try { f=found(h,142048); } finally { ConstructionConfig.TEST_DURATION.set((int)original); }
        var server=h.getLevel().getServer(); var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        var foundingMayor=NpcService.ensureMayor(h.getLevel(),f.settlement()).orElseThrow();
        for(int i=0;i<2;i++) ((Villager)h.getLevel().getEntity(foundingMayor.getPersistentData().getUUID("livingkingdoms:founding_resident_"+i))).setNoAi(true);
        supply(f.player(),entry.project().required(),0); h.assertTrue(ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id()),"Town Hall funded normally");
        h.assertTrue(!ConstructionService.resolve(server,entry.project().id(),f.player()),"Completion cannot happen on resource delivery tick");
        h.runAfterDelay(20,()->h.assertTrue(ConstructionSavedData.get(server).get(entry.project().id()).orElseThrow().project().state()==ConstructionState.BUILDING,"Timer persists before deadline"));
        h.runAfterDelay(41,()-> {
            ConstructionService.resolve(server,entry.project().id(),f.player());
            var storage=ConstructionSavedData.get(server); var hall=storage.get(entry.project().id()).orElseThrow();
            h.assertTrue(hall.project().state()==ConstructionState.COMPLETED,"Town Hall completes after configured time");
            h.assertTrue(hall.project().plot().equals(entry.project().plot()),"Completion uses saved plot and rotation");
            h.assertTrue(!ConstructionService.resolve(server,hall.project().id(),f.player(),true),"Completion receipt prevents a second placement");
            var house=ConstructionService.current(server,f.settlement().id()).orElseThrow(); h.assertTrue(house.project().building()==BuildingKind.HOUSE,"First House becomes the next project");
            supply(f.player(),house.project().required(),0); ConstructionService.deposit(f.player(),f.settlement().id(),house.project().id());
            h.assertTrue(ConstructionService.resolve(server,house.project().id(),f.player(),true),"Operator time skip can finish a funded House through normal placement");
            var data=SettlementSavedData.get(server); var settlement=data.get(f.settlement().id()).orElseThrow(); var layout=data.layout(settlement.id()).orElseThrow();
            h.assertTrue(settlement.lifecycle()==SettlementLifecycle.ESTABLISHED && layout.buildings().size()==3,"Town Hall + one House establishes the camp without utility buildings");
            h.assertTrue(layout.buildings().stream().anyMatch(b -> b.kind()==BuildingKind.HOUSE && b.origin().equals(house.plan().buildings().getFirst().origin())),"Completed House kind/position registered for future housing");
            h.assertTrue(settlement.id().equals(f.settlement().id()) && settlement.provenance().equals(f.settlement().provenance()) && settlement.population()==f.settlement().population(),"Progress preserves identity, founder and abstract population");
            ExpandedQuestService.prepare(f.player(),settlement);
            h.assertTrue(QuestSavedData.get(server).quests(f.player().getUUID(),settlement.id()).stream().anyMatch(q -> q.template()==QuestTemplate.MAIN_IRON),"Existing main quests resume after establishment"); h.succeed();
        });
        var mayor=NpcService.ensureMayor(h.getLevel(),f.settlement()).orElseThrow(); f.player().setPos(mayor.getX()+2,mayor.getY(),mayor.getZ());
        VillageUiService.openDialogue(f.player(),mayor);
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void saveReloadRetainsNativeBlueprintResourcesAndReservation(GameTestHelper h) {
        var f=found(h,143072); var server=h.getLevel().getServer(); var data=ConstructionSavedData.get(server); var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        supply(f.player(),entry.project().required(),0); ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id());
        var saved=data.save(new CompoundTag(),h.getLevel().registryAccess());
        var reloaded=ConstructionSavedData.load(saved,h.getLevel().registryAccess()); var loaded=reloaded.get(entry.project().id()).orElseThrow();
        try {
            var directory=java.nio.file.Files.createTempDirectory(serverPath(h.getLevel()),"construction-save-test-");
            var disk=new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(),server.getFixerUpper(),h.getLevel().registryAccess());
            var diskData=ConstructionSavedData.getOrCreate(disk,directory);
            var current=data.get(entry.project().id()).orElseThrow(); diskData.reserve(current.project(),current.plan()); disk.save();
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            var reopenedDisk=ConstructionSavedData.getOrCreate(new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(),server.getFixerUpper(),h.getLevel().registryAccess()),directory);
            h.assertTrue(reopenedDisk.get(current.project().id()).orElseThrow().project().equals(current.project()),"Actual DimensionDataStorage file reopens mid-construction");
        } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
        h.assertTrue(loaded.project().equals(data.get(entry.project().id()).orElseThrow().project()) && loaded.plan().before().equals(entry.plan().before())
                && loaded.plan().pathBlocks().equals(entry.plan().pathBlocks()) && loaded.plan().buildings().getFirst().module().template().save(new CompoundTag()).equals(entry.plan().buildings().getFirst().module().template().save(new CompoundTag())),"Mid-build save retains exact costs, time, supports, rotation, native template and terrain snapshots");
        boolean refused=false;
        try { reloaded.reserve(ConstructionProject.planned(f.settlement().id(),entry.project().building(),entry.project().plot(),entry.project().required(),100,ConstructionService.now(server)).waitForResources(),loaded.plan()); }
        catch(IllegalArgumentException expected) { refused=true; }
        h.assertTrue(refused && reloaded.reservations(f.settlement().id()).size()==1,"Reloaded reservation rejects another project on the same plot");
        var alternative=new SettlementGenerator().planBuildingAddition(h.getLevel(),f.settlement().id(),BuildingKind.HOUSE,new GenerationDiagnostics()).orElseThrow();
        h.assertTrue(!alternative.buildings().getFirst().bounds().conflicts(entry.plan().buildings().getFirst().bounds(),0),"Planner excludes pending reserved plots");
        var corrupt=saved.copy(); corrupt.getList("projects",10).getCompound(0).getCompound("project").getIntArray("plot")[0]+=1;
        boolean invalid=false; try { ConstructionSavedData.load(corrupt,h.getLevel().registryAccess()); } catch(IllegalArgumentException expected) { invalid=true; }
        h.assertTrue(invalid,"Mismatched project and blueprint fail closed"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void obstructionKeepsMaterialsAndRetryNeverMovesPlot(GameTestHelper h) {
        var f=found(h,144096); var server=h.getLevel().getServer(); var storage=ConstructionSavedData.get(server); var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        supply(f.player(),entry.project().required(),0); ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id());
        var obstacle=entry.plan().buildings().getFirst().origin().offset(2,1,2); h.getLevel().setBlock(obstacle,Blocks.CHEST.defaultBlockState(),18);
        h.assertTrue(!ConstructionService.resolve(server,entry.project().id(),f.player(),true) && h.getLevel().getBlockState(obstacle).is(Blocks.CHEST),"Changed/player-owned blocks cannot be overwritten");
        var failed=storage.get(entry.project().id()).orElseThrow().project(); h.assertTrue(failed.state()==ConstructionState.FAILED && failed.funded() && storage.reservations(f.settlement().id()).size()==1,"Failure retains supplied materials and reserved plot");
        h.getLevel().setBlock(obstacle,entry.plan().before().get(obstacle),18); h.assertTrue(ConstructionService.retry(f.player(),f.settlement().id(),failed.id()),"Survival retry uses existing receipt");
        h.assertTrue(ConstructionService.resolve(server,failed.id(),f.player(),true) && storage.get(failed.id()).orElseThrow().project().plot().equals(entry.project().plot()),"Retry completes the original blueprint without another delivery or new location"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void completionProtectionRollsBackStructureAndKeepsReceipt(GameTestHelper h) {
        var f=found(h,145120); var server=h.getLevel().getServer(); var storage=ConstructionSavedData.get(server); var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        supply(f.player(),entry.project().required(),0); ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id());
        Consumer<BlockEvent.EntityPlaceEvent> deny=event -> { if(event.getEntity()==f.player()) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(deny);
        try { h.assertTrue(!ConstructionService.resolve(server,entry.project().id(),f.player(),true),"Protection cancellation blocks completion"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.assertTrue(storage.get(entry.project().id()).orElseThrow().project().state()==ConstructionState.FAILED
                && storage.get(entry.project().id()).orElseThrow().project().funded() && h.getLevel().getBlockState(entry.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER),"Resource receipt and owned marker survive canceled placement");
        h.assertTrue(SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow().buildings().size()==1,"Canceled building is absent from metadata");
        for(var before:entry.plan().before().entrySet()) if(!before.getKey().equals(entry.marker())) h.assertTrue(h.getLevel().getBlockState(before.getKey()).equals(before.getValue()),"Exact terrain rollback after cancellation"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void constructionUiRejectsForeignSessionProjectAndOutOfReach(GameTestHelper h) {
        var f=found(h,146144); var entry=ConstructionService.current(h.getLevel().getServer(),f.settlement().id()).orElseThrow(); var player=f.player();
        player.setPos(entry.marker().getX()+2.5,entry.marker().getY(),entry.marker().getZ()+0.5);
        h.assertTrue(VillageUiService.openConstructionMarker(player,entry.marker()),"Physical construction marker opens supply UI");
        var token=player.snapshots.getLast().getUUID("session"); supply(player,entry.project().required(),0);
        var other=player(h.getLevel(),player.blockPosition()); supply(other,entry.project().required(),0);
        h.assertTrue(!VillageUiService.handle(other,new UiPayloads.Request(token,entry.project().id(),UiPayloads.Action.DEPOSIT)),"Another player cannot reuse a session token");
        h.assertTrue(!VillageUiService.handle(player,new UiPayloads.Request(token,UUID.randomUUID(),UiPayloads.Action.DEPOSIT)),"Forged project identity rejected");
        h.assertTrue(VillageUiService.handle(player,new UiPayloads.Request(token,entry.project().id(),UiPayloads.Action.DEPOSIT)),"Authorized UI deposit consumes materials");
        h.assertTrue(!VillageUiService.handle(player,new UiPayloads.Request(token,entry.project().id(),UiPayloads.Action.DEPOSIT)),"Replayed funded-project request cannot spend twice");
        player.setPos(entry.marker().getX()+100,entry.marker().getY(),entry.marker().getZ());
        h.assertTrue(!VillageUiService.handle(player,new UiPayloads.Request(token,entry.project().id(),UiPayloads.Action.REFRESH)),"Leaving invalidates anchor capability"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void temporaryEntityOccupancyRetriesWithoutFailingOrMovingPlot(GameTestHelper h) {
        var f=found(h,148192); var level=h.getLevel(); var server=level.getServer(); var storage=ConstructionSavedData.get(server);
        var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        supply(f.player(),entry.project().required(),0); ConstructionService.deposit(f.player(),f.settlement().id(),entry.project().id());
        var visitor=net.minecraft.world.entity.EntityType.VILLAGER.create(level); visitor.setNoAi(true);
        var at=entry.plan().buildings().getFirst().origin().offset(3,1,3); visitor.moveTo(at.getX()+0.5,at.getY(),at.getZ()+0.5,0,0); level.addFreshEntity(visitor);
        h.assertTrue(!ConstructionService.resolve(server,entry.project().id(),f.player(),true)
                && storage.get(entry.project().id()).orElseThrow().project().state()==ConstructionState.BUILDING
                && storage.waitingForClearSite(entry.project().id()),"An entity delays placement and schedules a bounded retry without a failed project");
        visitor.discard();
        h.assertTrue(ConstructionService.resolve(server,entry.project().id(),f.player(),true)
                && storage.get(entry.project().id()).orElseThrow().project().plot().equals(entry.project().plot()),"Cleared site completes the same funded blueprint"); h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void unloadedBlueprintDefersWithoutLoadingAndWakesOnChunkAvailability(GameTestHelper h) {
        var f=found(h,147168); var level=h.getLevel(); var server=level.getServer(); var source=ConstructionService.current(server,f.settlement().id()).orElseThrow();
        var destination=h.absolutePos(new BlockPos(2_100_000,0,2_100_000));
        int dx=destination.getX()-source.project().plot().x(), dz=destination.getZ()-source.project().plot().z(); var territory=source.plan().territory();
        var remote=new Territory(territory.dimension(),territory.x()+dx,territory.y(),territory.z()+dz,territory.radius());
        UUID id=UUID.randomUUID(); var settlement=Settlement.established(id,remote,5,SettlementOrigin.FOUNDED,f.player().getUUID());
        var metadata=SettlementSavedData.get(server).layout(f.settlement().id()).orElseThrow();
        var shiftedMetadata=new SettlementLayoutMetadata(metadata.style(),metadata.buildings().stream().map(b -> new SettlementLayoutMetadata.Building(b.kind(),b.template(),b.origin().offset(dx,0,dz),b.rotation(),shift(b.bounds(),dx,dz),b.entrance().offset(dx,0,dz))).toList(),
                metadata.ports().stream().map(p -> p.offset(dx,0,dz)).toList(),metadata.paths().stream().map(p -> p.offset(dx,0,dz)).toList());
        SettlementSavedData.get(server).add(settlement,shiftedMetadata);
        var b=source.plan().buildings().getFirst(); var plan=new SettlementLayout(remote,source.plan().style(),List.of(new SettlementLayout.Building(b.module(),b.origin().offset(dx,0,dz),b.rotation(),shift(b.bounds(),dx,dz),b.entrance().offset(dx,0,dz),b.supports().stream().map(p -> p.offset(dx,0,dz)).toList())),shift(source.plan().pathBlocks(),dx,dz),shift(source.plan().before(),dx,dz));
        long now=ConstructionService.now(server); var plot=source.project().plot();
        var p=ConstructionProject.planned(id,BuildingKind.TOWN_HALL,new ConstructionProject.Plot(plot.x()+dx,plot.y(),plot.z()+dz,plot.rotation()),source.project().required(),20,now).waitForResources();
        p=p.supply(p.required()).start(now); final UUID project=p.id(); ConstructionSavedData.get(server).reserve(p,plan);
        h.runAfterDelay(21,()-> {
            ConstructionService.resolve(server,project,null); var data=ConstructionSavedData.get(server); var deferred=data.get(project).orElseThrow();
            h.assertTrue(deferred.project().awaitingChunks() && deferred.project().funded() && !level.getChunkSource().hasChunk(deferred.marker().getX()>>4,deferred.marker().getZ()>>4),"Due unloaded project retains materials and never force-loads its chunk");
            var reloaded=ConstructionSavedData.load(data.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            h.assertTrue(reloaded.get(project).orElseThrow().project().awaitingChunks() && reloaded.ready(ConstructionService.now(server),64).contains(project),"Deferred completion survives restart and schedules one initial availability check");
            // Test-only loading: reproduce the saved preconditions, then notify the real chunk index.
            for(var pos:plan.before().keySet()) level.getChunk(pos.getX()>>4,pos.getZ()>>4);
            for(var e:plan.before().entrySet()) level.setBlock(e.getKey(),e.getValue(),18);
            // This synthetic remote reservation bypassed ConstructionService.reserve while its
            // chunks were absent. Restore its owned reservation marker together with the terrain.
            level.setBlock(deferred.marker(),KingdomBlocks.CONSTRUCTION_MARKER.get().defaultBlockState(),18);
            var chunk=new ChunkPos(deferred.marker()); level.getChunkSource().addRegionTicket(FIXTURE,chunk,4,chunk);
            data.chunkLoaded(remote.dimension(),chunk);
            h.assertTrue(data.ready(ConstructionService.now(server),64).contains(project),"Relevant chunk load wakes deferred project");
            h.assertTrue(ConstructionService.resolve(server,project,null) && data.get(project).orElseThrow().project().state()==ConstructionState.COMPLETED,"Loaded project safely places the persisted blueprint exactly once"); h.succeed();
        });
    }
    private record Founded(Settlement settlement,UiTestPlayer player) {}
    private static java.nio.file.Path serverPath(ServerLevel level) { return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT); }
    private static Founded found(GameTestHelper h,int coordinate) {
        var center=fixture(h,coordinate); var player=player(h.getLevel(),center);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(KingdomItems.KINGDOM_CHARTER.get()));
        var result=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
        h.assertTrue(result.successful(),"Progressive founding: "+result.message().getString()+" / "+result.diagnostics()); return new Founded(result.settlement(),player);
    }
    private static UiTestPlayer player(ServerLevel level,BlockPos center) {
        // NeoForge's default FakePlayerAdvancements intentionally refuses all awards.
        var p=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } };
        p.getAbilities().mayBuild=true; p.getAbilities().instabuild=false; p.setPos(center.getX()+0.5,center.getY(),center.getZ()+2.5); return p;
    }
    private static void supply(UiTestPlayer player,Map<ResourceKind,Integer> cost,int extra) {
        player.getInventory().clearContent(); int slot=0;
        for(var kind:ResourceKind.values()) if(cost.containsKey(kind)) {
            Item item=switch(kind) { case LOGS -> Items.OAK_LOG; case STONE -> Items.STONE; case IRON_INGOT -> Items.IRON_INGOT; case WHEAT -> Items.WHEAT; };
            int remaining=cost.get(kind)+extra; while(remaining>0) { int n=Math.min(64,remaining); player.getInventory().items.set(slot++,new ItemStack(item,n)); remaining-=n; }
        }
    }
    private static PlotBounds shift(PlotBounds b,int dx,int dz) { return new PlotBounds(b.minX()+dx,b.minZ()+dz,b.maxX()+dx,b.maxZ()+dz); }
    private static Map<BlockPos,net.minecraft.world.level.block.state.BlockState> shift(Map<BlockPos,net.minecraft.world.level.block.state.BlockState> map,int dx,int dz) {
        Map<BlockPos,net.minecraft.world.level.block.state.BlockState> result=new LinkedHashMap<>(); map.forEach((p,s) -> result.put(p.offset(dx,0,dz),s)); return result;
    }
    private static BlockPos fixture(GameTestHelper h,int coordinate) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=72;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,6,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
            level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=-3;y<-1;y++) level.setBlock(center.offset(x,y,z),Blocks.DIRT.defaultBlockState(),18);
            for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        } return center;
    }
}

package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.SettlementGenerationConfig.Tolerance;
import dev.livingkingdoms.construction.ConstructionStages;
import dev.livingkingdoms.construction.domain.ConstructionProject;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

import java.util.*;
import java.util.function.Consumer;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionStageGameTests {
    private static final TicketType<ChunkPos> OCCUPANCY_FIXTURE=TicketType.create(
            "livingkingdoms_construction_occupancy_test",Comparator.comparingLong(ChunkPos::toLong),400);
    @GameTest(template="empty", timeoutTicks=600)
    public static void fiveBuildingTypesAllRotationsHaveConnectedStagesAndExactNativeFinal(GameTestHelper h) {
        var catalog = BuildingCatalog.load(h.getLevel(), ArchitectureStyle.PLAINS);
        int index = 0;
        for (var kind : List.of(BuildingKind.HOUSE, BuildingKind.FARM, BuildingKind.BARRACKS,
                BuildingKind.WATCHTOWER, BuildingKind.TOWN_HALL)) for (var rotation : Rotation.values()) {
            var entry = fixture(h, catalog.get(kind), rotation, 330000 + index % 5 * 32, 330000 + index / 5 * 32);
            index++;
            var plan = entry.plan(); var building = plan.buildings().getFirst();
            var snapshots = ConstructionStages.stages(plan);
            h.assertTrue(snapshots.size() == 5, "Five deterministic construction masks");
            boolean immutable = false;
            try { snapshots.getFirst().put(entry.marker(), Blocks.DIAMOND_BLOCK.defaultBlockState()); }
            catch (UnsupportedOperationException expected) { immutable = true; }
            h.assertTrue(immutable, "Stage snapshots are immutable");
            for (int stage = 0; stage <= 4; stage++) {
                try (var transaction = ConstructionStages.apply(h.getLevel(), entry, stage - 1, stage, null)) {
                    h.assertTrue(transaction.result() == ConstructionStages.Result.APPLIED,
                            "Placed " + kind + "/" + rotation + "/stage " + stage + ": " + transaction.failure());
                    transaction.commit();
                }
                var expected = snapshots.get(stage);
                for (var block : expected.entrySet()) h.assertTrue(h.getLevel().getBlockState(block.getKey()).equals(block.getValue()),
                        "Exact stage state " + kind + "/" + rotation + "/" + stage + "/" + block.getKey());
                if (stage < 4) {
                    Set<BlockPos> visible = new HashSet<>();
                    for (var block : expected.entrySet()) if (building.volume().isInside(block.getKey()) && !block.getValue().isAir()) {
                        h.assertTrue(block.getValue().getFluidState().isEmpty() && h.getLevel().getBlockEntity(block.getKey()) == null
                                && !(block.getValue().getBlock() instanceof BedBlock) && !(block.getValue().getBlock() instanceof DoorBlock),
                                "Intermediate masks defer fluids, inventories, and paired blocks");
                        visible.add(block.getKey());
                    }
                    assertConnected(h, visible, building.origin().getY(), kind + "/" + rotation + "/" + stage);
                    h.assertTrue(h.getLevel().getBlockState(entry.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER), "Temporary marker is owned until final");
                } else {
                    for (var info : building.module().blocks()) h.assertTrue(h.getLevel().getBlockState(building.position(info.pos())).equals(info.state().rotate(rotation)),
                            "Final native export remains authoritative");
                    h.assertTrue(!h.getLevel().getBlockState(entry.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER), "Final export removes temporary marker");
                }
                var size = building.module().rotatedSize(rotation);
                for (int x=-1;x<=size.getX();x++) for (int z=-1;z<=size.getZ();z++) for (int y=0;y<=13;y++) {
                    var pos=building.origin().offset(x,y,z);
                    if (!building.volume().isInside(pos)) h.assertTrue(h.getLevel().getBlockState(pos).isAir(), "No floating planes or writes outside rotated bounds");
                }
            }
        }
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void unrelatedBlocksAndInventoriesAreNeverOverwrittenByStages(GameTestHelper h) {
        var entry=fixture(h,BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS).get(BuildingKind.HOUSE),Rotation.CLOCKWISE_90,331024,331024);
        var level=h.getLevel(); var at=entry.plan().buildings().getFirst().origin().offset(3,1,3);
        level.setBlock(at,Blocks.DIAMOND_BLOCK.defaultBlockState(),18);
        try(var transaction=ConstructionStages.apply(level,entry,-1,0,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"Player edit blocks the stage before any writes");
        }
        h.assertTrue(level.getBlockState(at).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(entry.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER),"Player block and owned marker survive rejection");
        level.setBlock(at,Blocks.CHEST.defaultBlockState(),18);
        var chest=(Container)level.getBlockEntity(at); chest.setItem(0,new ItemStack(Items.DIAMOND,7));
        try(var transaction=ConstructionStages.apply(level,entry,-1,4,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"Container inventories block final placement");
        }
        h.assertTrue(level.getBlockEntity(at)==chest && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount()==7,"Original inventory is neither destroyed nor duplicated");
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void uncommittedAndProtectionCanceledStagesRestorePreviousVisualReceipt(GameTestHelper h) {
        var entry=fixture(h,BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS).get(BuildingKind.BARRACKS),Rotation.COUNTERCLOCKWISE_90,332048,332048);
        var level=h.getLevel();
        try(var transaction=ConstructionStages.apply(level,entry,-1,0,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.APPLIED,"Site placed synchronously");
            // Deliberately omit commit: a failed receipt CAS must restore the exact reservation.
        }
        assertSnapshot(h,entry,-1);
        try(var transaction=ConstructionStages.apply(level,entry,-1,0,null)) { transaction.commit(); }
        var actor=new UiTestPlayer(level); actor.getAbilities().mayBuild=true;
        actor.setPos(entry.marker().getX()-2.5,entry.marker().getY(),entry.marker().getZ()-2.5);
        Consumer<BlockEvent.EntityPlaceEvent> deny=event -> { if(event.getEntity()==actor) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(deny);
        try {
            try(var transaction=ConstructionStages.apply(level,entry,0,2,actor)) {
                h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"Protection cancellation blocks intermediate stage");
            }
            assertSnapshot(h,entry,0);
            try(var transaction=ConstructionStages.apply(level,entry,0,4,actor)) {
                h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"Protection cancellation also blocks native final placement");
            }
            assertSnapshot(h,entry,0);
        } finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void interruptedSaveAdoptsOnlyExactWholeStageAndEmptyNativeFinal(GameTestHelper h) {
        var entry=fixture(h,BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS).get(BuildingKind.HOUSE),Rotation.CLOCKWISE_180,333072,333072);
        var level=h.getLevel();
        try(var transaction=ConstructionStages.apply(level,entry,-1,0,null)) { transaction.commit(); }
        try(var transaction=ConstructionStages.apply(level,entry,-1,0,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.APPLIED,"Exact target stage is adopted after world save outruns receipt save"); transaction.commit();
        }
        var corner=entry.plan().buildings().getFirst().origin(); level.setBlock(corner,Blocks.AIR.defaultBlockState(),18);
        try(var transaction=ConstructionStages.apply(level,entry,-1,0,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"Partial target cannot be silently adopted or repaired");
        }
        level.setBlock(corner,ConstructionStages.expected(entry,0).get(corner),18);
        try(var transaction=ConstructionStages.apply(level,entry,0,4,null)) { transaction.commit(); }
        try(var transaction=ConstructionStages.apply(level,entry,0,4,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.APPLIED,"Complete exact native final can be adopted without placing a second copy"); transaction.commit();
        }
        var barrel=entry.plan().buildings().getFirst().module().blocks().stream().filter(info -> info.state().is(Blocks.BARREL)).findFirst().orElseThrow();
        var container=(Container)level.getBlockEntity(entry.plan().buildings().getFirst().position(barrel.pos()));
        container.setItem(0,new ItemStack(Items.EMERALD,3));
        try(var transaction=ConstructionStages.apply(level,entry,0,4,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"A changed completed container cannot be adopted from stale stage ownership");
        }
        h.assertTrue(container.getItem(0).getCount()==3,"Stale receipt never clears stored player items"); h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void registeredSharedPathsAreAcceptedAtFinalButNeverTouchedDuringStages(GameTestHelper h) {
        var original=fixture(h,BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS).get(BuildingKind.FARM),Rotation.NONE,334096,334096);
        var level=h.getLevel(); var path=original.marker().west(2).below(2);
        for(int y=0;y<=3;y++) { level.getChunkAt(path.above(y)); level.setBlock(path.above(y),y==0?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState(),18); }
        Map<BlockPos,BlockState> before=new HashMap<>(original.plan().before()); Map<BlockPos,BlockState> paths=new HashMap<>();
        for(int y=0;y<=3;y++) { before.put(path.above(y),level.getBlockState(path.above(y))); paths.put(path.above(y),y==0?Blocks.DIRT_PATH.defaultBlockState():Blocks.AIR.defaultBlockState()); }
        var plan=new SettlementLayout(original.plan().territory(),original.plan().style(),original.plan().buildings(),paths,before);
        var entry=new ConstructionSavedData.Entry(original.project(),plan);
        level.setBlock(path,Blocks.DIRT_PATH.defaultBlockState(),18); // An earlier queued project completed this registered route.
        try(var transaction=ConstructionStages.apply(level,entry,-1,3,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.APPLIED,"Intermediate stages ignore shared path changes"); transaction.commit();
        }
        h.assertTrue(level.getBlockState(path).is(Blocks.DIRT_PATH),"No intermediate path writes");
        try(var transaction=ConstructionStages.apply(level,entry,3,4,null)) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED,"Unregistered changed route blocks final placement");
        }
        try(var transaction=ConstructionStages.apply(level,entry,3,4,null,Set.of(path))) {
            h.assertTrue(transaction.result()==ConstructionStages.Result.APPLIED,"Exact registered shared paving is accepted"); transaction.commit();
        }
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void onlyAssignedWorkerMayOccupyUnchangedExteriorClearanceDuringFinal(GameTestHelper h) {
        var original=fixture(h,BuildingCatalog.load(h.getLevel(),ArchitectureStyle.PLAINS).get(BuildingKind.HOUSE),Rotation.NONE,335120,335120);
        var level=h.getLevel(); var path=original.marker().west(2).below(2);
        Map<BlockPos,BlockState> before=new HashMap<>(original.plan().before()); Map<BlockPos,BlockState> paths=new HashMap<>();
        for(int y=0;y<=3;y++) {
            var position=path.above(y); var state=y==0?Blocks.DIRT_PATH.defaultBlockState():Blocks.AIR.defaultBlockState();
            level.getChunkAt(position); level.setBlock(position,state,18); before.put(position,state); paths.put(position,state);
        }
        var plan=new SettlementLayout(original.plan().territory(),original.plan().style(),original.plan().buildings(),paths,before);
        var entry=new ConstructionSavedData.Entry(original.project(),plan);
        // Block chunks alone do not make a remote fixture's entities visible to spatial queries.
        // This ticket belongs only to the test; production construction never requests one.
        var chunk=new ChunkPos(path); level.getChunkSource().addRegionTicket(OCCUPANCY_FIXTURE,chunk,3,chunk);
        var inside=entry.plan().buildings().getFirst().origin().offset(1,1,1);
        var worker=net.minecraft.world.entity.EntityType.VILLAGER.create(level); worker.setNoAi(true);
        worker.moveTo(path.getX()+0.5,path.getY()+1,path.getZ()+0.5,0,0);
        h.assertTrue(level.addFreshEntity(worker),"Native occupancy fixture joins the world");
        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(level.isPositionEntityTicking(path.above()) && level.isPositionEntityTicking(inside)
                    && level.areEntitiesLoaded(new ChunkPos(path).toLong()) && level.areEntitiesLoaded(new ChunkPos(inside).toLong()),
                    "Both exterior and interior fixture chunks must finish native entity activation");
            h.assertTrue(level.getEntities((net.minecraft.world.entity.Entity)null,new AABB(path.above()),entity -> entity==worker).contains(worker),
                    "Wait for native entity tracking before exercising occupancy guards");
        }).thenExecute(() -> {
        try {
            try(var transaction=ConstructionStages.apply(level,entry,-1,4,null,Set.of(path))) {
                h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED && transaction.occupied(),
                        "Ordinary placement still blocks an entity in exterior path clearance: "+transaction.result()+"/"+transaction.failure());
            }
            try(var transaction=ConstructionStages.apply(level,entry,-1,4,null,Set.of(path),Set.of(worker.getUUID()))) {
                h.assertTrue(transaction.result()==ConstructionStages.Result.APPLIED,
                        "The assigned worker can finish while standing in unchanged exterior air: "+transaction.failure());
                // Leave uncommitted so the same reservation can exercise unsafe positions below.
            }
            assertSnapshot(h,entry,-1);
            worker.moveTo(inside.getX()+0.5,inside.getY(),inside.getZ()+0.5,0,0);
            h.assertTrue(level.getEntities((net.minecraft.world.entity.Entity)null,new AABB(inside),entity -> entity==worker).contains(worker),
                    "Moved native worker is tracked inside the future building");
            try(var transaction=ConstructionStages.apply(level,entry,-1,4,null,Set.of(path),Set.of(worker.getUUID()))) {
                h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED && transaction.occupied(),
                        "Worker exemption never allows an occupied new building body: "+transaction.result()+"/"+transaction.failure());
            }
            assertSnapshot(h,entry,-1);
            worker.moveTo(path.getX()+0.5,path.getY()+1,path.getZ()+0.5,0,0);
            var solidPath=new HashMap<>(paths); solidPath.put(path.above(),Blocks.COBBLESTONE.defaultBlockState());
            var unsafePlan=new SettlementLayout(plan.territory(),plan.style(),plan.buildings(),solidPath,before);
            var unsafe=new ConstructionSavedData.Entry(entry.project(),unsafePlan);
            try(var transaction=ConstructionStages.apply(level,unsafe,-1,4,null,Set.of(path),Set.of(worker.getUUID()))) {
                h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED && transaction.occupied(),
                        "Worker exemption never allows a changed solid path write at the worker");
            }
            assertSnapshot(h,entry,-1);
            var visitor=new UiTestPlayer(level); visitor.getAbilities().mayBuild=true;
            visitor.setPos(path.getX()+0.5,path.getY()+1,path.getZ()+0.5); level.addNewPlayer(visitor);
            try {
                try(var transaction=ConstructionStages.apply(level,entry,-1,4,null,Set.of(path),Set.of(worker.getUUID()))) {
                    h.assertTrue(transaction.result()==ConstructionStages.Result.BLOCKED && transaction.occupied(),
                            "An unrelated player still blocks native placement in the worker's exterior clearance");
                }
                assertSnapshot(h,entry,-1);
            } finally { level.removePlayerImmediately(visitor,net.minecraft.world.entity.Entity.RemovalReason.DISCARDED); }
        } finally {
            worker.discard(); level.getChunkSource().removeRegionTicket(OCCUPANCY_FIXTURE,chunk,3,chunk);
        }
        }).thenSucceed();
    }

    private static ConstructionSavedData.Entry fixture(GameTestHelper h,BuildingTemplate module,Rotation rotation,int x,int z) {
        var level=h.getLevel(); var origin=h.absolutePos(new BlockPos(x,1,z)); var size=module.rotatedSize(rotation);
        for(int dx=-2;dx<=size.getX()+1;dx++) for(int dz=-2;dz<=size.getZ()+1;dz++) {
            var at=origin.offset(dx,0,dz); level.getChunkAt(at);
            for(int y=-3;y<0;y++) level.setBlock(at.above(y),Blocks.DIRT.defaultBlockState(),18);
            for(int y=0;y<=13;y++) level.setBlock(at.above(y),Blocks.AIR.defaultBlockState(),18);
        }
        var territory=new Territory(level.dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ(),32);
        var plan=new SettlementLayoutPlanner().planPlot(level,territory,module,origin,rotation,new Tolerance(3,1,3),new GenerationDiagnostics()).orElseThrow();
        var project=ConstructionProject.planned(UUID.randomUUID(),module.kind(),new ConstructionProject.Plot(origin.getX(),origin.getY(),origin.getZ(),
                ConstructionProject.Orientation.valueOf(rotation.name())),Map.of(ResourceKind.LOGS,1),20,0).waitForResources();
        var entry=new ConstructionSavedData.Entry(project,plan);
        level.setBlock(entry.marker(),KingdomBlocks.CONSTRUCTION_MARKER.get().defaultBlockState(),18);
        return entry;
    }
    private static void assertSnapshot(GameTestHelper h,ConstructionSavedData.Entry entry,int stage) {
        for(var block:ConstructionStages.expected(entry,stage).entrySet()) h.assertTrue(h.getLevel().getBlockState(block.getKey()).equals(block.getValue()),"Exact rollback to stage "+stage+" at "+block.getKey());
    }
    private static void assertConnected(GameTestHelper h,Set<BlockPos> visible,int floor,String label) {
        Set<BlockPos> visited=new HashSet<>(); ArrayDeque<BlockPos> pending=new ArrayDeque<>();
        visible.stream().filter(pos -> pos.getY()==floor).forEach(pending::add);
        while(!pending.isEmpty()) {
            var at=pending.removeFirst(); if(!visible.contains(at)||!visited.add(at)) continue;
            for(var direction:Direction.values()) pending.addLast(at.relative(direction));
        }
        var detached=new HashSet<>(visible); detached.removeAll(visited);
        h.assertTrue(detached.isEmpty(),"No floating or disconnected stage fragments in "+label+": "+detached.stream().limit(8).toList());
    }
}

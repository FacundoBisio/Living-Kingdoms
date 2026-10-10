package dev.livingkingdoms.construction;

import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.structure.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Queue/index/CAS behavior uses a minimal geometry-only blueprint and never mutates a world. */
class BuilderQueueTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static ConstructionSavedData.Entry project(UUID settlement,int x,boolean builder) {
        var p=ConstructionProject.planned(settlement,BuildingKind.HOUSE,
                new ConstructionProject.Plot(x,70,10,ConstructionProject.Orientation.NONE),Map.of(ResourceKind.LOGS,10),100,1000);
        if(builder) p=p.requiringBuilder(); p=p.waitForResources(); p=p.supply(p.required());
        var nativeTag=new CompoundTag(); var size=new ListTag(); size.add(IntTag.valueOf(3)); size.add(IntTag.valueOf(3)); size.add(IntTag.valueOf(3));
        nativeTag.put("size",size); nativeTag.put("palette",new ListTag()); nativeTag.put("blocks",new ListTag()); nativeTag.put("entities",new ListTag());
        var template=new StructureTemplate(); template.load(BuiltInRegistries.BLOCK.asLookup(),nativeTag);
        var module=new BuildingTemplate(BuildingKind.HOUSE,ResourceLocation.fromNamespaceAndPath("livingkingdoms","queue_test"),template,
                new Vec3i(3,3,3),List.of(),new BlockPos(1,0,3));
        var origin=new BlockPos(x,70,10); var building=new SettlementLayout.Building(module,origin,Rotation.NONE,
                new PlotBounds(x,10,x+2,12),origin.offset(1,0,3),List.of());
        var plan=new SettlementLayout(new Territory("minecraft:overworld",0,70,0,96),ArchitectureStyle.PLAINS,List.of(building),Map.of(),Map.of());
        return new ConstructionSavedData.Entry(p,plan);
    }
    @Test void fundedProjectsRemainFifoAndPendingSettlementsRotateWithoutScanningWorld() {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(); var data=new ConstructionSavedData();
        var first=project(a,10,true); var second=project(a,20,true); var other=project(b,30,true);
        data.reserve(first.project(),first.plan()); data.reserve(second.project(),second.plan()); data.reserve(other.project(),other.plan());
        assertEquals(List.of(first,second),data.readyProjects(a)); assertEquals(List.of(a),data.pendingSettlements(1));
        assertEquals(List.of(b),data.pendingSettlements(1)); assertEquals(List.of(a,b),data.pendingSettlements(10));
        assertEquals(0,data.buildingCount(a)); assertTrue(data.ready(Long.MAX_VALUE,10).isEmpty());
    }
    @Test void builderOwnershipIsExclusiveAndOldUiReceiptsCannotMutateTheProjectTwice() {
        UUID settlement=UUID.randomUUID(), builder=UUID.randomUUID(); var data=new ConstructionSavedData();
        var first=project(settlement,10,true); var second=project(settlement,20,true);
        data.reserve(first.project(),first.plan()); data.reserve(second.project(),second.plan());
        var started=first.project().start(1010,builder); assertTrue(data.replace(first.project(),started));
        assertEquals(first.project().id(),data.forBuilder(builder).orElseThrow().project().id()); assertEquals(1,data.buildingCount(settlement));
        assertFalse(data.replace(first.project(),first.project().start(1010,UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,()->data.replace(second.project(),second.project().start(1010,builder)));
        assertEquals(second.project(),data.get(second.project().id()).orElseThrow().project());
        var progress=started.addWork(1030,20); assertTrue(data.replace(started,progress)); assertFalse(data.replace(started,progress));
        assertFalse(data.replace(progress,progress.addWork(1030,20))); assertEquals(20,data.forBuilder(builder).orElseThrow().project().workTicks());
    }
    @Test void replacementBuilderContinuesExistingReceiptAndFifoPosition() {
        UUID settlement=UUID.randomUUID(), builder=UUID.randomUUID(); var data=new ConstructionSavedData();
        var first=project(settlement,10,true); var second=project(settlement,20,true);
        data.reserve(first.project(),first.plan()); data.reserve(second.project(),second.plan());
        var active=first.project().start(1010,builder).stagePlaced(0).addWork(1030,30).stagePlaced(1).awardStageXp(1);
        assertTrue(data.replace(first.project(),active)); var paused=active.releaseBuilder(); assertTrue(data.replace(active,paused));
        assertTrue(data.forBuilder(builder).isEmpty()); assertEquals(0,data.buildingCount(settlement));
        assertEquals(List.of(first.project().id(),second.project().id()),data.readyProjects(settlement).stream().map(e -> e.project().id()).toList());
        var resumed=paused.start(2010,UUID.randomUUID()); assertTrue(data.replace(paused,resumed));
        assertEquals(30,data.get(first.project().id()).orElseThrow().project().workTicks()); assertEquals(1,resumed.visualStage());
        assertTrue(resumed.stageXpAwarded(1)); assertEquals(1,data.buildingCount(settlement));
    }
    @Test void legacyDeadlineSchedulingAndBuildingCountsRemainAvailable() {
        UUID settlement=UUID.randomUUID(); var data=new ConstructionSavedData(); var entry=project(settlement,10,false);
        data.reserve(entry.project(),entry.plan()); var started=entry.project().start(1010); assertTrue(data.replace(entry.project(),started));
        assertEquals(1,data.buildingCount(settlement)); assertTrue(data.ready(1109,10).isEmpty());
        assertEquals(List.of(started.id()),data.ready(1110,10)); assertTrue(data.ready(1110,10).isEmpty());
        var done=started.complete(1110); assertTrue(data.replace(started,done)); assertEquals(0,data.buildingCount(settlement));
        assertTrue(data.pendingSettlements(10).isEmpty()); assertFalse(data.replace(started,done));
    }
    @Test void receiptHistoryCannotChangeDurationOwnerModeOrAlreadyCreditedWork() {
        UUID settlement=UUID.randomUUID(); var data=new ConstructionSavedData(); var entry=project(settlement,10,true);
        data.reserve(entry.project(),entry.plan()); var started=entry.project().start(1010,UUID.randomUUID()); assertTrue(data.replace(entry.project(),started));
        var progress=started.addWork(1030,20); assertTrue(data.replace(started,progress));
        assertThrows(IllegalArgumentException.class,()->data.replace(progress,started));
        var rewritten=new ConstructionProject(progress.id(),progress.settlementId(),progress.building(),progress.plot(),progress.state(),
                progress.required(),progress.supplied(),200,progress.createdAt(),progress.startedAt(),progress.completedAt(),false,true,
                progress.builderId(),progress.workTicks(),progress.lastWorkAt(),progress.visualStage(),progress.awardedStages());
        assertThrows(IllegalArgumentException.class,()->data.replace(progress,rewritten));
    }
    @Test void reservationsStillRejectOverlapsAndRetainFootprintsAfterCompletion() {
        UUID settlement=UUID.randomUUID(); var data=new ConstructionSavedData(); var entry=project(settlement,10,true);
        data.reserve(entry.project(),entry.plan()); var collision=project(settlement,11,true);
        assertThrows(IllegalArgumentException.class,()->data.reserve(collision.project(),collision.plan()));
        var active=entry.project().start(1010,UUID.randomUUID()); assertTrue(data.replace(entry.project(),active));
        assertTrue(data.replace(active,active.debugComplete(1030))); assertTrue(data.reservations(settlement).isEmpty());
        assertThrows(IllegalArgumentException.class,()->data.reserve(collision.project(),collision.plan()));
    }
    @Test void queueAndBuilderIndexesReleaseOnOperatorCompletionOfFundedUnassignedProject() {
        UUID settlement=UUID.randomUUID(); var data=new ConstructionSavedData(); var entry=project(settlement,10,true);
        data.reserve(entry.project(),entry.plan()); var staged=entry.project().debugAdvance(1010,2);
        assertTrue(data.replace(entry.project(),staged)); assertEquals(List.of(settlement),data.pendingSettlements(1));
        assertTrue(data.replace(staged,staged.debugComplete(1020))); assertEquals(0,data.buildingCount(settlement));
        assertTrue(data.pendingSettlements(10).isEmpty()); assertTrue(data.readyProjects(settlement).isEmpty());
    }
}

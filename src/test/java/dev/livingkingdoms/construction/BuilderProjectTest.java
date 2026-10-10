package dev.livingkingdoms.construction;

import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.construction.persistence.ConstructionNbt;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.BuildingKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderProjectTest {
    private static ConstructionProject planned() {
        return ConstructionProject.planned(UUID.randomUUID(),BuildingKind.HOUSE,
                new ConstructionProject.Plot(10,70,20,ConstructionProject.Orientation.CLOCKWISE_90),
                Map.of(ResourceKind.LOGS,20),100,1000).requiringBuilder();
    }
    private static ConstructionProject ready() { var p=planned().waitForResources(); return p.supply(p.required()); }
    private static ConstructionProject building() { return ready().start(1010,UUID.randomUUID()); }

    @Test void fullResourcesWaitForAnAssignedBuilder() {
        var p=ready(); assertEquals(ConstructionState.READY,p.state()); assertTrue(p.funded());
        assertNull(p.builderId()); assertFalse(p.due(Long.MAX_VALUE));
        assertThrows(IllegalStateException.class,()->p.start(1010));
        UUID builder=UUID.randomUUID(); var active=p.start(1010,builder);
        assertEquals(builder,active.builderId()); assertEquals(ConstructionState.BUILDING,active.state());
        assertEquals(1010,active.lastWorkAt()); assertFalse(active.due(Long.MAX_VALUE));
    }
    @Test void workUsesCreditedIntervalsAndDuplicateTickReceiptsAreNoOps() {
        var p=building(); assertSame(p,p.addWork(1010,20));
        var work=p.addWork(1030,20); assertEquals(20,work.workTicks()); assertEquals(.2,work.progress(Long.MAX_VALUE));
        assertSame(work,work.addWork(1030,20)); assertSame(work,work.addWork(1020,20));
        assertEquals(80,work.remainingWork()); assertFalse(work.due(999999));
        assertThrows(IllegalStateException.class,()->work.addWork(1009,20));
        assertThrows(IllegalStateException.class,()->work.addWork(1040,0));
    }
    @Test void creditedWorkCapsAtConfiguredDurationWithoutOverflow() {
        var p=building().addWork(1030,Long.MAX_VALUE);
        assertEquals(100,p.workTicks()); assertEquals(0,p.remainingWork()); assertTrue(p.due(1030));
        assertEquals(1,p.progress(1030)); assertEquals(4,p.targetStage());
    }
    @Test void builderReplacementPreservesMaterialsProgressStageAndMilestoneReceipts() {
        var original=building().stagePlaced(0).addWork(1030,30).stagePlaced(1).awardStageXp(1);
        var paused=original.releaseBuilder(); assertEquals(ConstructionState.READY,paused.state()); assertNull(paused.builderId());
        assertEquals(original.startedAt(),paused.startedAt()); assertEquals(original.supplied(),paused.supplied());
        assertEquals(30,paused.workTicks()); assertEquals(1,paused.visualStage()); assertTrue(paused.stageXpAwarded(1));
        UUID replacement=UUID.randomUUID(); var resumed=paused.start(2000,replacement);
        assertEquals(replacement,resumed.builderId()); assertEquals(original.startedAt(),resumed.startedAt());
        assertEquals(30,resumed.workTicks()); assertEquals(2000,resumed.lastWorkAt());
        assertSame(resumed,resumed.addWork(2000,100));
    }
    @Test void stageTargetsFollowWorkAndPlacementIsSeparatelyPersisted() {
        var p=building(); assertEquals(-1,p.visualStage()); assertEquals(0,p.targetStage());
        var site=p.stagePlaced(0); assertEquals(0,site.visualStage());
        assertThrows(IllegalStateException.class,()->site.stagePlaced(1));
        assertEquals(0,site.addWork(1030,24).targetStage());
        assertEquals(1,site.addWork(1030,25).targetStage());
        assertEquals(2,site.addWork(1030,50).targetStage());
        assertEquals(3,site.addWork(1030,75).targetStage());
        assertEquals(4,site.addWork(1030,100).targetStage());
        var walls=site.addWork(1030,50).stagePlaced(2);
        assertThrows(IllegalStateException.class,()->walls.stagePlaced(1));
    }
    @Test void completionIsTerminalAndMilestoneXpCanBeRecordedOnlyOnce() {
        var p=building().stagePlaced(0).addWork(1030,100).stagePlaced(4);
        var reward=p.awardStageXp(4); assertTrue(reward.stageXpAwarded(4)); assertSame(reward,reward.awardStageXp(4));
        var complete=reward.complete(1030); assertEquals(ConstructionState.COMPLETED,complete.state());
        assertEquals(4,complete.visualStage()); assertEquals(0,complete.remainingWork());
        assertThrows(IllegalStateException.class,()->complete.complete(1031));
        assertThrows(IllegalStateException.class,()->complete.addWork(1031,20));
        assertThrows(IllegalStateException.class,()->complete.releaseBuilder());
        assertThrows(IllegalStateException.class,()->building().awardStageXp(1));
    }
    @Test void placementRetryPreservesFundedWorkAndReturnsToQueueForBuilderReassignment() {
        var p=building().stagePlaced(0).addWork(1030,50).stagePlaced(2).awardStageXp(2);
        var failed=p.fail(); var retry=failed.retry(); assertEquals(ConstructionState.READY,retry.state());
        assertNull(retry.builderId()); assertEquals(p.workTicks(),retry.workTicks()); assertEquals(p.visualStage(),retry.visualStage());
        assertEquals(p.awardedStages(),retry.awardedStages()); assertEquals(p.supplied(),retry.supplied());
    }
    @Test void everyBuilderStateRoundTripsIncludingPausedAndFailedPartiallyBuiltProjects() {
        var planned=planned(); var waiting=planned.waitForResources(); var partial=waiting.supply(Map.of(ResourceKind.LOGS,3));
        var ready=waiting.supply(waiting.required()); var building=ready.start(1010,UUID.randomUUID());
        var work=building.stagePlaced(0).addWork(1030,50).stagePlaced(2).awardStageXp(2);
        var due=work.addWork(1050,50).stagePlaced(4);
        for(var p:List.of(planned,waiting,partial,ready,building,work,work.releaseBuilder(),work.fail(),work.fail().retry(),due.defer(1050),due.complete(1050)))
            assertEquals(p,ConstructionNbt.readProject(ConstructionNbt.writeProject(p)));
    }
    @Test void oldProjectsMigrateWithUnchangedDeadlineMaterialsAndLegacyBehavior() {
        var p=ConstructionProject.planned(UUID.randomUUID(),BuildingKind.HOUSE,
                new ConstructionProject.Plot(10,70,20,ConstructionProject.Orientation.NONE),Map.of(ResourceKind.LOGS,20),100,1000).waitForResources();
        p=p.supply(p.required()).start(1010); var t=ConstructionNbt.writeProject(p);
        for(String field:List.of("builder_required","builder","work_ticks","last_work","visual_stage","awarded_stages")) t.remove(field);
        var migrated=ConstructionNbt.readProject(t); assertEquals(p,migrated); assertFalse(migrated.builderRequired());
        assertEquals(1110,migrated.deadline()); assertEquals(.5,migrated.progress(1060)); assertTrue(migrated.due(1110));
        assertEquals(p.supplied(),migrated.complete(1110).supplied());
    }
    @Test void incompleteNewSchemaAndInvalidWorkStageOrBuilderReceiptsFailClosed() {
        var missing=ConstructionNbt.writeProject(building()); missing.remove("last_work");
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(missing));
        var noBuilder=ConstructionNbt.writeProject(building()); noBuilder.remove("builder");
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(noBuilder));
        var excessive=ConstructionNbt.writeProject(building()); excessive.putLong("work_ticks",101);
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(excessive));
        var invalidStage=ConstructionNbt.writeProject(building()); invalidStage.putInt("visual_stage",5);
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(invalidStage));
        var invalidXp=ConstructionNbt.writeProject(building()); invalidXp.putInt("awarded_stages",2);
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(invalidXp));
    }
    @Test void debugStagesCanBeExaminedWithoutBuilderAndConsumeAllSkippedXpReceipts() {
        var p=ready().debugAdvance(1010,2); assertEquals(ConstructionState.READY,p.state()); assertNull(p.builderId());
        assertEquals(50,p.workTicks()); assertEquals(2,p.visualStage());
        assertTrue(p.stageXpAwarded(0)); assertTrue(p.stageXpAwarded(1)); assertTrue(p.stageXpAwarded(2));
        assertFalse(p.stageXpAwarded(3)); assertEquals(p,ConstructionNbt.readProject(ConstructionNbt.writeProject(p)));
        var completed=p.debugComplete(1020); assertEquals(ConstructionState.COMPLETED,completed.state());
        assertEquals(100,completed.workTicks()); assertEquals(31,completed.awardedStages());
        assertThrows(IllegalStateException.class,()->completed.debugComplete(1030));
    }
    @Test void operatorsCanCompleteFundedLegacyProjectsWithoutChangingTheirNormalSurvivalPolicy() {
        var p=ConstructionProject.planned(UUID.randomUUID(),BuildingKind.HOUSE,
                new ConstructionProject.Plot(10,70,20,ConstructionProject.Orientation.NONE),Map.of(ResourceKind.LOGS,20),100,1000).waitForResources();
        assertThrows(IllegalStateException.class,()->p.debugComplete(1010));
        var ready=p.supply(p.required()); var complete=ready.debugComplete(1010);
        assertFalse(complete.builderRequired()); assertEquals(1010,complete.startedAt()); assertEquals(1010,complete.completedAt());
        assertEquals(complete,ConstructionNbt.readProject(ConstructionNbt.writeProject(complete)));
        var timed=ready.start(1010); assertEquals(2,timed.targetStage(1060)); assertFalse(timed.due(1060));
    }
    @Test void foundingDebugStagesPreserveTheOriginalLegacyDeadlineAndGrantNoBuilderXp() {
        var waiting=ConstructionProject.planned(UUID.randomUUID(),BuildingKind.TOWN_HALL,
                new ConstructionProject.Plot(10,70,20,ConstructionProject.Orientation.NONE),Map.of(ResourceKind.LOGS,20),100,1000).waitForResources();
        var started=waiting.supply(waiting.required()).start(1010); var roof=started.debugAdvance(1011,3);
        assertFalse(roof.builderRequired()); assertEquals(3,roof.visualStage()); assertEquals(1110,roof.deadline());
        assertEquals(0,roof.workTicks()); assertEquals(-1,roof.lastWorkAt()); assertEquals(0,roof.awardedStages());
        assertFalse(roof.due(1011)); assertTrue(roof.due(1110)); assertEquals(roof,ConstructionNbt.readProject(ConstructionNbt.writeProject(roof)));
        assertThrows(IllegalStateException.class,()->roof.debugAdvance(1012,2));
        assertThrows(IllegalStateException.class,()->roof.debugAdvance(1012,4));
    }
}

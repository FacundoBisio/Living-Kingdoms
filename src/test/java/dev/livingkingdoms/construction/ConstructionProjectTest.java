package dev.livingkingdoms.construction;

import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.construction.persistence.ConstructionNbt;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.BuildingKind;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionProjectTest {
    private static ConstructionProject planned() {
        return ConstructionProject.planned(UUID.randomUUID(),BuildingKind.TOWN_HALL,
                new ConstructionProject.Plot(17,71,-29,ConstructionProject.Orientation.CLOCKWISE_90),
                Map.of(ResourceKind.LOGS,64,ResourceKind.STONE,32,ResourceKind.IRON_INGOT,4),100,1000);
    }
    private static ConstructionProject building() { var p=planned().waitForResources(); return p.supply(p.required()).start(1010); }
    @Test void partialMaterialsAreSharedAndBounded() {
        var p=planned().waitForResources().supply(Map.of(ResourceKind.LOGS,20));
        assertEquals(ConstructionState.WAITING_FOR_RESOURCES,p.state()); assertEquals(44,p.missing(ResourceKind.LOGS));
        var second=p.supply(Map.of(ResourceKind.LOGS,44,ResourceKind.STONE,32,ResourceKind.IRON_INGOT,4));
        assertTrue(second.funded()); assertEquals(ConstructionState.READY,second.state());
        assertThrows(IllegalStateException.class,()->second.supply(Map.of(ResourceKind.LOGS,1)));
        assertThrows(IllegalArgumentException.class,()->p.supply(Map.of(ResourceKind.LOGS,45)));
        assertThrows(IllegalArgumentException.class,()->p.supply(Map.of(ResourceKind.WHEAT,1)));
        assertEquals(20,p.supplied().get(ResourceKind.LOGS));
    }
    @Test void durationCannotFinishInTheStartTickOrBeforeTheDeadline() {
        var p=building(); assertFalse(p.due(1010)); assertFalse(p.due(1109)); assertTrue(p.due(1110));
        assertEquals(0.5,p.progress(1060)); assertEquals(0,p.progress(0)); assertEquals(1,p.progress(Long.MAX_VALUE));
        assertThrows(IllegalStateException.class,()->p.complete(1109));
        var done=p.complete(1110); assertEquals(1110,done.completedAt()); assertEquals(1,done.progress(1110));
        assertThrows(IllegalStateException.class,()->done.complete(1111));
    }
    @Test void unloadingAndRetryKeepTheReceiptAndStablePlot() {
        var p=building(); var pending=p.defer(1110);
        assertTrue(pending.awaitingChunks()); assertEquals(p.supplied(),pending.supplied()); assertEquals(p.plot(),pending.plot());
        var failed=pending.fail(); var retry=failed.retry();
        assertEquals(p.supplied(),retry.supplied()); assertEquals(p.startedAt(),retry.startedAt()); assertTrue(retry.due(1110));
        assertEquals(p.id(),retry.id()); assertFalse(retry.awaitingChunks());
    }
    @Test void everyRealStageRoundTripsIncludingMidBuildAndDeferredCompletion() {
        var p=planned(); var waiting=p.waitForResources(); var partial=waiting.supply(Map.of(ResourceKind.LOGS,20));
        var ready=waiting.supply(waiting.required()); var building=ready.start(1010);
        for(var stage:java.util.List.of(p,waiting,partial,ready,building,building.defer(1110),building.fail(),building.complete(1110)))
            assertEquals(stage,ConstructionNbt.readProject(ConstructionNbt.writeProject(stage)));
    }
    @Test void malformedAndFutureRecordsFailClosed() {
        final var saved=ConstructionNbt.writeProject(building()); saved.remove("started");
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(saved));
        var future=ConstructionNbt.writeProject(building()); future.putString("state","FUTURE");
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(future));
        var invalid=ConstructionNbt.writeProject(building()); invalid.getCompound("supplied").putInt("LOGS",65);
        assertThrows(IllegalArgumentException.class,()->ConstructionNbt.readProject(invalid));
    }
    @Test void invalidDurationsAndUnfundedStartsCannotCreateInstantFreeBuildings() {
        var p=planned().waitForResources(); assertThrows(IllegalStateException.class,()->p.start(1000));
        assertThrows(IllegalArgumentException.class,()->ConstructionProject.planned(p.settlementId(),p.building(),p.plot(),p.required(),0,1000));
        assertThrows(IllegalArgumentException.class,()->ConstructionProject.planned(p.settlementId(),BuildingKind.CORE,p.plot(),p.required(),100,1000));
        assertThrows(UnsupportedOperationException.class,()->p.required().put(ResourceKind.LOGS,1));
    }
}

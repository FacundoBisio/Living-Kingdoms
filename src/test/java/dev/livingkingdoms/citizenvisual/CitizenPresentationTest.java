package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CitizenPresentationTest {
    private static final UUID ID = UUID.fromString("ab43be0e-1d72-4a92-ab00-03ea02ca7281");
    private static CitizenPresentation data(ProfessionType profession, WorkState state, boolean active, boolean moving, long xp) {
        return new CitizenPresentation(5, ID, "Ada del Río", 4, profession, 2, state, active, moving, true, "farm", xp, "");
    }
    @Test void walkingWorkingFlagsAndInitialTrackingNeverFabricateWorkActions() {
        var before = data(ProfessionType.FARMER, WorkState.WORKING, true, false, 10);
        assertFalse(before.performedWorkSince(null));
        assertFalse(before.performedWorkSince(before));
        assertFalse(data(ProfessionType.FARMER, WorkState.WORKING, true, true, 15).performedWorkSince(before));
        assertFalse(data(ProfessionType.FARMER, WorkState.BLOCKED, true, false, 15).performedWorkSince(before));
        assertFalse(data(ProfessionType.FARMER, WorkState.WORKING, false, false, 15).performedWorkSince(before));
        assertFalse(data(ProfessionType.MINER, WorkState.WORKING, true, false, 15).performedWorkSince(before));
        assertTrue(data(ProfessionType.FARMER, WorkState.WORKING, true, false, 15).performedWorkSince(before));
    }
    @Test void identitySelectsStableBoundedSkinsAcrossProfessionsAndSignedUuids() {
        var variants = new HashSet<Integer>();
        for (int i = -100; i < 100; i++) {
            UUID id = new UUID(i, ~i);
            int variant = CitizenPresentation.skinVariant(id);
            assertTrue(variant >= 0 && variant < 8);
            assertEquals(variant, CitizenPresentation.skinVariant(UUID.fromString(id.toString())));
            variants.add(variant);
        }
        assertEquals(8, variants.size());
        assertEquals(data(ProfessionType.FARMER, WorkState.IDLE, true, false, 0).skinVariant(),
                data(ProfessionType.MAYOR, WorkState.IDLE, true, false, 0).skinVariant());
    }
    @Test void professionsWithExistingServicesRequireSneakingForInspection() {
        assertFalse(CitizenVisualService.inspection(ProfessionType.MAYOR, false));
        assertFalse(CitizenVisualService.inspection(ProfessionType.MERCHANT, false));
        for (var type : ProfessionType.values()) assertTrue(CitizenVisualService.inspection(type, true));
        assertTrue(CitizenVisualService.inspection(ProfessionType.FARMER, false));
    }
    @Test void cosmeticToolsFollowRealAssignmentsAndNeverInventGuardWeapons() {
        assertTrue(data(ProfessionType.FARMER, WorkState.WORKING, true, false, 0).showsProfessionTool());
        assertTrue(data(ProfessionType.BUILDER, WorkState.REPAIRING, true, false, 0).showsProfessionTool());
        assertTrue(data(ProfessionType.MAYOR, WorkState.IDLE, true, false, 0).showsProfessionTool());
        for (var state : WorkState.values()) {
            assertFalse(data(ProfessionType.GUARD, state, true, false, 0).showsProfessionTool());
            assertFalse(data(ProfessionType.CAPTAIN, state, true, false, 0).showsProfessionTool());
            assertFalse(data(ProfessionType.FARMER, state, false, false, 0).showsProfessionTool());
            assertFalse(data(ProfessionType.FARMER, state, true, true, 0).showsProfessionTool());
        }
        assertFalse(data(ProfessionType.MINER, WorkState.BLOCKED, true, false, 0).showsProfessionTool());
        assertFalse(data(ProfessionType.MAYOR, WorkState.SLEEPING, true, false, 0).showsProfessionTool());
    }
}

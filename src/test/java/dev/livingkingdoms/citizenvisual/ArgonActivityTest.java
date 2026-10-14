package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArgonActivityTest {
    private CitizenPresentation data(ProfessionType job, WorkState state, boolean active, boolean travelling) {
        return new CitizenPresentation(1, UUID.fromString("00000000-0000-0000-0000-000000000001"), "Vale", 1,
                job, 1, state, active, travelling, true, "", 10);
    }
    @Test void selectsOnlyImplementedRealWorkAndRetainsNativeMinerMerchantGuardAnimation() {
        for (var job : ProfessionType.values()) {
            String expected = switch (job) {
                case BUILDER, BLACKSMITH -> "work_hammer";
                case FARMER -> "farm_plant";
                case LUMBERJACK -> "lumberjack_chop";
                default -> null;
            };
            assertEquals(expected, ArgonActivity.workClip(data(job, WorkState.WORKING, true, false), 5, 0, false, false, false));
        }
    }
    @Test void noWorkOnFirstTrackingReconnectOrExpiredPulse() {
        var worker = data(ProfessionType.BUILDER, WorkState.WORKING, true, false);
        for (float elapsed : new float[]{-1000, -1, 32, 400, Float.NaN, Float.POSITIVE_INFINITY})
            assertNull(ArgonActivity.workClip(worker, elapsed, 0, false, false, false));
        assertEquals("work_hammer", ArgonActivity.workClip(worker, 0, 0, false, false, false));
    }
    @Test void restMovementCombatAndUnloadedStatesNeverPretendToWork() {
        for (var state : WorkState.values()) {
            String result = ArgonActivity.workClip(data(ProfessionType.BUILDER, state, true, false), 5, 0, false, false, false);
            if (state == WorkState.WORKING || state == WorkState.REPAIRING) assertNotNull(result); else assertNull(result);
        }
        var worker = data(ProfessionType.BLACKSMITH, WorkState.WORKING, true, false);
        assertNull(ArgonActivity.workClip(worker, 5, .1F, false, false, false));
        assertNull(ArgonActivity.workClip(worker, 5, Float.NaN, false, false, false));
        assertNull(ArgonActivity.workClip(worker, 5, 0, true, false, false));
        assertNull(ArgonActivity.workClip(worker, 5, 0, false, true, false));
        assertNull(ArgonActivity.workClip(worker, 5, 0, false, false, true));
        assertNull(ArgonActivity.workClip(data(ProfessionType.BUILDER, WorkState.WORKING, false, false), 5, 0, false, false, false));
        assertNull(ArgonActivity.workClip(data(ProfessionType.BUILDER, WorkState.WORKING, true, true), 5, 0, false, false, false));
    }
}

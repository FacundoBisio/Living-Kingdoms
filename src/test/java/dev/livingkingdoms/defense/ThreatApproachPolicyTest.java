package dev.livingkingdoms.defense;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThreatApproachPolicyTest {
    @Test void annulusHonorsDistancesAndWholeGroupClearanceOutsideTerritory() {
        assertFalse(ThreatApproachPolicy.validOrigin(69,48,70,140));
        assertTrue(ThreatApproachPolicy.validOrigin(70,48,70,140));
        assertTrue(ThreatApproachPolicy.validOrigin(140,48,70,140));
        assertFalse(ThreatApproachPolicy.validOrigin(141,48,70,140));
        assertFalse(ThreatApproachPolicy.validOrigin(100,100,70,140));
        assertFalse(ThreatApproachPolicy.validOrigin(106,100,70,140));
        assertTrue(ThreatApproachPolicy.validOrigin(107,100,70,140));
        assertFalse(ThreatApproachPolicy.validOrigin(100,48,140,70));
    }
    @Test void objectiveIsCircularAndAcceptsBoundaryWithoutInfinitePursuit() {
        assertTrue(ThreatApproachPolicy.within(64,0,0,0,64));
        assertFalse(ThreatApproachPolicy.within(64.01,0,0,0,64));
        assertFalse(ThreatApproachPolicy.within(50,50,0,0,64));
        assertTrue(ThreatApproachPolicy.within(-10,-10,-10,-10,0));
    }
}

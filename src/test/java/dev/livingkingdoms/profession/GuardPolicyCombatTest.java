package dev.livingkingdoms.profession;

import dev.livingkingdoms.profession.domain.GuardPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuardPolicyCombatTest {
    @Test
    void cadenceKeepsTheLevelOneBaseAndSpeedsUpTwoTicksPerLevelToTheMinimum() {
        assertEquals(25, GuardPolicy.attackCooldown(1, 15));
        assertEquals(23, GuardPolicy.attackCooldown(2, 15));
        assertEquals(17, GuardPolicy.attackCooldown(5, 15));
        assertEquals(15, GuardPolicy.attackCooldown(6, 15));
        assertEquals(15, GuardPolicy.attackCooldown(20, 15));
        assertEquals(25, GuardPolicy.attackCooldown(20, 25));
        assertEquals(5, GuardPolicy.attackCooldown(20, 5));
        assertEquals(25, GuardPolicy.attackCooldown(0, 15));
        for (int level = 1; level < 20; level++)
            assertTrue(GuardPolicy.attackCooldown(level + 1, 5) <= GuardPolicy.attackCooldown(level, 5));
    }

    @Test
    void alertWidensSearchWithinTheDefenseAreaAndTheHardCap() {
        assertEquals(16, GuardPolicy.searchRadius(16, false, 2.0, 32));
        assertEquals(32, GuardPolicy.searchRadius(16, true, 2.0, 32));
        assertEquals(16, GuardPolicy.searchRadius(16, true, 1.0, 32));
        assertEquals(48, GuardPolicy.searchRadius(24, true, 3.0, 64));
        assertEquals(20, GuardPolicy.searchRadius(16, true, 3.0, 10));
        assertEquals(16, GuardPolicy.searchRadius(16, true, 3.0, 4));
    }

    @Test
    void shieldRisesOnlyForCloseHostilesBetweenAttacks() {
        assertTrue(GuardPolicy.raiseShield(true, true, 9, false));
        assertTrue(GuardPolicy.raiseShield(true, true, 16, false));
        assertFalse(GuardPolicy.raiseShield(true, true, 16.5, false));
        assertFalse(GuardPolicy.raiseShield(true, true, 1, true));
        assertFalse(GuardPolicy.raiseShield(false, true, 1, false));
        assertFalse(GuardPolicy.raiseShield(true, false, 1, false));
    }
}

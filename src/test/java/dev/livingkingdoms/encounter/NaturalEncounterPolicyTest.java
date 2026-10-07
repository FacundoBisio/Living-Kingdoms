package dev.livingkingdoms.encounter;

import dev.livingkingdoms.encounter.domain.PartyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NaturalEncounterPolicyTest {
    @Test
    void worldSettingsAndPersistedCooldownEachGateAttempts() {
        assertTrue(NaturalEncounterPolicy.canAttempt(true, true, false, true, 2400, 2400));
        assertFalse(NaturalEncounterPolicy.canAttempt(false, true, false, true, 2400, 2400));
        assertFalse(NaturalEncounterPolicy.canAttempt(true, false, false, true, 2400, 2400));
        assertFalse(NaturalEncounterPolicy.canAttempt(true, true, true, true, 2400, 2400));
        assertFalse(NaturalEncounterPolicy.canAttempt(true, true, false, false, 2400, 2400));
        assertFalse(NaturalEncounterPolicy.canAttempt(true, true, false, true, 2399, 2400));
    }

    @Test
    void bothRegionalAndGlobalBudgetsRejectTheLimitItself() {
        assertTrue(NaturalEncounterPolicy.hasCapacity(1, 2, 255, 256));
        assertFalse(NaturalEncounterPolicy.hasCapacity(2, 2, 12, 256));
        assertFalse(NaturalEncounterPolicy.hasCapacity(0, 2, 256, 256));
        assertFalse(NaturalEncounterPolicy.hasCapacity(0, 0, 0, 256));
    }

    @Test
    void spacingUsesCircularHorizontalDistanceAndIncludesTheMinimum() {
        assertFalse(NaturalEncounterPolicy.separated(0, 0, 127, 0, 128));
        assertTrue(NaturalEncounterPolicy.separated(0, 0, 128, 0, 128));
        assertFalse(NaturalEncounterPolicy.separated(0, 0, 90, 90, 128));
        assertTrue(NaturalEncounterPolicy.separated(0, 0, 91, 91, 128));
        assertTrue(NaturalEncounterPolicy.separated(-400, -600, -528, -600, 128));
    }

    @Test
    void extremeCoordinatesAndZeroSpacingDoNotOverflow() {
        assertTrue(NaturalEncounterPolicy.separated(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0, 128));
        assertTrue(NaturalEncounterPolicy.separated(0, 0, 0, 0, 0));
        assertFalse(NaturalEncounterPolicy.separated(Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE + 10, Integer.MAX_VALUE - 10, 128));
        assertThrows(IllegalArgumentException.class, () -> NaturalEncounterPolicy.separated(0, 0, 0, 0, -1));
    }

    @Test
    void allDaytimeProposalsArePillagers() {
        for (int roll = 0; roll < 4; roll++) assertEquals(PartyType.PILLAGER_PATROL, NaturalEncounterPolicy.typeForTime(false, roll));
    }

    @Test
    void nightFavorsUndeadWithoutEliminatingPillagers() {
        for (int roll = 0; roll < 3; roll++) assertEquals(PartyType.UNDEAD_HORDE, NaturalEncounterPolicy.typeForTime(true, roll));
        assertEquals(PartyType.PILLAGER_PATROL, NaturalEncounterPolicy.typeForTime(true, 3));
        assertThrows(IllegalArgumentException.class, () -> NaturalEncounterPolicy.typeForTime(true, 4));
    }

    @Test
    void undeadRequireNightAndUnlitEnoughTerrain() {
        assertTrue(NaturalEncounterPolicy.undeadAllowed(true, 0));
        assertTrue(NaturalEncounterPolicy.undeadAllowed(true, 7));
        assertFalse(NaturalEncounterPolicy.undeadAllowed(true, 8));
        assertFalse(NaturalEncounterPolicy.undeadAllowed(false, 0));
        assertFalse(NaturalEncounterPolicy.undeadAllowed(true, -1));
    }

    @Test
    void cooldownCannotWrapIntoAnImmediatelyEligibleTimestamp() {
        assertEquals(2600, NaturalEncounterPolicy.nextAttempt(200, 2400));
        assertEquals(Long.MAX_VALUE, NaturalEncounterPolicy.nextAttempt(Long.MAX_VALUE - 1, 2400));
        assertThrows(IllegalArgumentException.class, () -> NaturalEncounterPolicy.nextAttempt(0, 0));
        assertThrows(IllegalArgumentException.class, () -> NaturalEncounterPolicy.nextAttempt(-1, 2400));
    }
}

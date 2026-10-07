package dev.livingkingdoms.faction;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class FactionTest {
    @Test
    void stableIdentifiersRoundTripAcrossAllInitialFactions() {
        HashSet<String> ids = new HashSet<>();
        for (Faction faction : Faction.values()) {
            assertTrue(ids.add(faction.id()));
            assertSame(faction, Faction.fromId(faction.id()));
            assertEquals(faction == Faction.ALLIED_KINGDOM, faction.isAllied());
        }
        assertEquals(4, ids.size());
        assertThrows(IllegalArgumentException.class, () -> Faction.fromId("missing"));
    }

    @Test
    void legacyAlliedIdentityMigratesToCanonicalKingdom() {
        assertSame(Faction.ALLIED_KINGDOM, Faction.fromId("allied"));
        assertSame(Faction.ALLIED_KINGDOM, Faction.ALLIED);
        assertEquals("allied_kingdom", Faction.fromId("allied").id());
    }

    @Test
    void baselineRelationshipsAreSymmetricAndDoNotAssumeHostileFactionAlliances() {
        for (Faction first : Faction.values()) {
            for (Faction second : Faction.values()) {
                FactionRelation expected = first == second ? FactionRelation.ALLY
                        : first.isAllied() || second.isAllied() || first == Faction.UNDEAD || second == Faction.UNDEAD
                        ? FactionRelation.HOSTILE : FactionRelation.NEUTRAL;
                assertEquals(expected, FactionRelations.between(first, second));
                assertEquals(FactionRelations.between(first, second), FactionRelations.between(second, first));
            }
        }
        assertThrows(NullPointerException.class, () -> FactionRelations.between(null, Faction.UNDEAD));
    }

    @Test
    void pillagersAndBanditsAreHostileToUndeadButNeutralToEachOther() {
        assertTrue(FactionRelations.isHostile(Faction.PILLAGER, Faction.UNDEAD));
        assertTrue(FactionRelations.isHostile(Faction.UNDEAD, Faction.PILLAGER));
        assertTrue(FactionRelations.isHostile(Faction.BANDIT, Faction.UNDEAD));
        assertFalse(FactionRelations.isHostile(Faction.PILLAGER, Faction.BANDIT));
        assertEquals(FactionRelation.NEUTRAL, FactionRelations.getRelation(Faction.BANDIT, Faction.PILLAGER));
    }

    @Test
    void noFactionCanBeHostileToItselfAndCompatibilityApiDelegatesToPolicy() {
        for (Faction first : Faction.values()) {
            assertFalse(FactionRelations.isHostile(first, first));
            assertEquals(FactionRelation.ALLY, FactionRelations.getRelation(first, first));
            for (Faction second : Faction.values()) {
                assertEquals(FactionRelations.between(first, second), FactionRelations.getRelation(first, second));
            }
        }
        assertThrows(NullPointerException.class, () -> FactionRelations.isHostile(Faction.UNDEAD, null));
    }
}

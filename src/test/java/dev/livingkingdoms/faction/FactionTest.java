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
                        : first.isAllied() || second.isAllied() ? FactionRelation.HOSTILE : FactionRelation.NEUTRAL;
                assertEquals(expected, FactionRelations.between(first, second));
                assertEquals(FactionRelations.between(first, second), FactionRelations.between(second, first));
            }
        }
        assertThrows(NullPointerException.class, () -> FactionRelations.between(null, Faction.UNDEAD));
    }
}

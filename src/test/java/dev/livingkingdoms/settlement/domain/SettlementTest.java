package dev.livingkingdoms.settlement.domain;

import dev.livingkingdoms.faction.Faction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementTest {
    @Test
    void foundingCreatesAnAlliedSettlementWithStableIdentity() {
        UUID id = UUID.randomUUID();
        Territory territory = new Territory("minecraft:overworld", -12, 70, 34, 48);
        Settlement settlement = Settlement.founding(id, territory, 5);
        assertEquals(id, settlement.id());
        assertEquals(territory, settlement.territory());
        assertEquals(Faction.ALLIED, settlement.faction());
        assertEquals(1, settlement.level());
        assertEquals(5, settlement.population());
        assertFalse(settlement.name().isBlank());
    }

    @Test
    void invalidSettlementStateIsRejected() {
        UUID id = UUID.randomUUID();
        Territory territory = new Territory("minecraft:overworld", 0, 64, 0, 48);
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(id, " ", Faction.ALLIED, 1, 5, territory));
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(id, "Haven", Faction.ALLIED, 0, 5, territory));
        assertThrows(IllegalArgumentException.class,
                () -> new Settlement(id, "Haven", Faction.ALLIED, 1, -1, territory));
        assertThrows(IllegalArgumentException.class, () -> Faction.fromId("missing"));
    }

    @Test
    void territoriesRespectBoundaryAndDimension() {
        Territory territory = new Territory("minecraft:overworld", 0, 64, 0, 5);
        assertTrue(territory.contains("minecraft:overworld", 3, 4));
        assertFalse(territory.contains("minecraft:overworld", 3, 5));
        assertFalse(territory.contains("minecraft:the_nether", 0, 0));
        assertTrue(territory.overlaps(new Territory("minecraft:overworld", 10, 10, 0, 5)));
        assertFalse(territory.overlaps(new Territory("minecraft:overworld", 11, 64, 0, 5)));
        assertFalse(territory.overlaps(new Territory("minecraft:the_nether", 0, 64, 0, 5)));
    }

    @Test
    void distanceMathDoesNotOverflowAtLargeCoordinates() {
        Territory territory = new Territory("minecraft:overworld", Integer.MIN_VALUE, 0, 0, 48);
        assertFalse(territory.contains("minecraft:overworld", Integer.MAX_VALUE, 0));
        assertFalse(territory.overlaps(new Territory("minecraft:overworld", Integer.MAX_VALUE, 0, 0, 48)));
    }
}

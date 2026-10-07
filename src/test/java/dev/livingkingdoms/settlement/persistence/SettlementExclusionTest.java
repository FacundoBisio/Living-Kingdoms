package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementExclusionTest {
    private static Settlement settlement(Faction faction, String dimension, int x, int z, int radius) {
        return new Settlement(UUID.randomUUID(), "Test", faction, 1, 5, new Territory(dimension, x, 64, z, radius));
    }

    @Test void alliedTerritoryPlusBufferHasInclusiveBoundary() {
        SettlementSavedData data = new SettlementSavedData();
        data.add(settlement(Faction.ALLIED_KINGDOM, "minecraft:overworld", -256, -256, 48));
        assertTrue(data.nearAlliedTerritory("minecraft:overworld", -192, -256, 16));
        assertFalse(data.nearAlliedTerritory("minecraft:overworld", -191, -256, 16));
        assertFalse(data.nearAlliedTerritory("minecraft:overworld", -192, -192, 16));
        assertThrows(IllegalArgumentException.class, () -> data.nearAlliedTerritory("minecraft:overworld", 0, 0, -1));
    }

    @Test void hostileAndOtherDimensionTerritoriesDoNotExcludeOverworldCandidates() {
        SettlementSavedData data = new SettlementSavedData();
        data.add(settlement(Faction.PILLAGER, "minecraft:overworld", 0, 0, 48));
        data.add(settlement(Faction.ALLIED_KINGDOM, "minecraft:the_nether", 0, 0, 48));
        assertFalse(data.nearAlliedTerritory("minecraft:overworld", 0, 0, 16));
        assertTrue(data.nearAlliedTerritory("minecraft:the_nether", 0, 0, 16));
    }

    @Test void importedLargeTerritoriesRemainExcludedAfterReload() {
        SettlementSavedData data = new SettlementSavedData();
        data.add(settlement(Faction.ALLIED_KINGDOM, "minecraft:overworld", 0, 0, 7000));
        SettlementSavedData reopened = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(reopened.nearAlliedTerritory("minecraft:overworld", 7016, 0, 16));
        assertFalse(reopened.nearAlliedTerritory("minecraft:overworld", 7017, 0, 16));
    }

    @Test void extremeCoordinatesDoNotOverflowTerritoryExclusion() {
        SettlementSavedData data = new SettlementSavedData();
        data.add(settlement(Faction.ALLIED_KINGDOM, "minecraft:overworld", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertFalse(data.nearAlliedTerritory("minecraft:overworld", Integer.MAX_VALUE, Integer.MAX_VALUE, 16));
        assertTrue(data.nearAlliedTerritory("minecraft:overworld", -100, Integer.MIN_VALUE, 16));
    }
}

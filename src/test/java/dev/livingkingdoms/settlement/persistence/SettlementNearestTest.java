package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementNearestTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static Settlement settlement(UUID id, Faction faction, String dimension, int x, int z) {
        return new Settlement(id, faction.id() + " test", faction, 1, 0,
                new Territory(dimension, x, 64, z, 1));
    }

    private static Settlement settlement(Faction faction, String dimension, int x, int z) {
        return settlement(UUID.randomUUID(), faction, dimension, x, z);
    }

    @Test
    void allFactionsRoundTripThroughSavedSettlementMetadata() {
        SettlementSavedData data = new SettlementSavedData();
        int x = 0;
        for (Faction faction : Faction.values()) {
            data.add(settlement(faction, OVERWORLD, x, 0));
            x += 256;
        }
        SettlementSavedData reopened = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(data.settlements(), reopened.settlements());
        assertEquals(List.of(Faction.ALLIED_KINGDOM, Faction.PILLAGER, Faction.BANDIT, Faction.UNDEAD),
                reopened.settlements().stream().map(Settlement::faction).toList());
        assertEquals(data.settlements().getFirst(), reopened.nearestAllied(OVERWORLD, 0, 0, 0).orElseThrow());
        assertFalse(reopened.isDirty());
    }

    @Test
    void oldAlliedSaveLoadsWithoutSchemaChangeAndWritesCanonicalFaction() {
        Settlement original = settlement(Faction.ALLIED_KINGDOM, OVERWORLD, 10, 20);
        SettlementSavedData data = new SettlementSavedData();
        data.add(original);
        CompoundTag old = data.save(new CompoundTag(), null);
        old.getList("settlements", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("faction", "allied");
        SettlementSavedData migrated = SettlementSavedData.load(old, null);
        assertEquals(original, migrated.get(original.id()).orElseThrow());
        assertEquals(original, migrated.nearestAllied(OVERWORLD, 10, 20, 0).orElseThrow());
        CompoundTag saved = migrated.save(new CompoundTag(), null);
        assertEquals(3, saved.getInt("schema_version"));
        assertEquals("allied_kingdom", saved.getList("settlements", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).getString("faction"));
    }

    @Test
    void nearestIgnoresCloserHostilesAndOtherDimensions() {
        SettlementSavedData data = new SettlementSavedData();
        data.add(settlement(Faction.PILLAGER, OVERWORLD, 0, 0));
        data.add(settlement(Faction.BANDIT, OVERWORLD, 10, 0));
        data.add(settlement(Faction.UNDEAD, OVERWORLD, 20, 0));
        Settlement otherDimension = settlement(Faction.ALLIED_KINGDOM, NETHER, 0, 0);
        Settlement allied = settlement(Faction.ALLIED_KINGDOM, OVERWORLD, 100, 0);
        data.add(otherDimension);
        data.add(allied);
        data.add(settlement(Faction.ALLIED_KINGDOM, OVERWORLD, 200, 0));
        assertEquals(allied, data.nearestAllied(OVERWORLD, 0, 0, 100).orElseThrow());
        assertEquals(otherDimension, data.nearestAllied(NETHER, 0, 0, 0).orElseThrow());
        assertTrue(data.nearestAllied(OVERWORLD, 0, 0, 99).isEmpty());
        assertTrue(data.nearestAllied("minecraft:the_end", 0, 0, 4096).isEmpty());
    }

    @Test
    void distanceUsesAnInclusiveCircleAroundCenters() {
        SettlementSavedData data = new SettlementSavedData();
        Settlement allied = settlement(Faction.ALLIED_KINGDOM, OVERWORLD, 3, 4);
        data.add(allied);
        assertEquals(allied, data.nearestAllied(OVERWORLD, 0, 0, 5).orElseThrow());
        assertTrue(data.nearestAllied(OVERWORLD, 0, 0, 4).isEmpty());
        assertTrue(data.nearestAllied(OVERWORLD, -2, -1, 5).isEmpty());
        assertEquals(allied, data.nearestAllied(OVERWORLD, 3, 4, 0).orElseThrow());
    }

    @Test
    void tiesUseStableIdentityInsteadOfInsertionOrderAndIndexRebuildsOnLoad() {
        UUID lowId = new UUID(0, 1);
        UUID highId = new UUID(0, 2);
        Settlement lower = settlement(lowId, Faction.ALLIED_KINGDOM, OVERWORLD, -32, 0);
        Settlement higher = settlement(highId, Faction.ALLIED_KINGDOM, OVERWORLD, 32, 0);
        SettlementSavedData data = new SettlementSavedData();
        data.add(higher);
        data.add(lower);
        assertEquals(lower, data.nearestAllied(OVERWORLD, 0, 0, 32).orElseThrow());
        SettlementSavedData loaded = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(lower, loaded.nearestAllied(OVERWORLD, 0, 0, 32).orElseThrow());
        assertEquals(higher, loaded.get(highId).orElseThrow());
        assertTrue(loaded.get(new UUID(0, 3)).isEmpty());
    }

    @Test
    void negativeBucketBoundariesAndExtremeCoordinatesDoNotOverflow() {
        SettlementSavedData data = new SettlementSavedData();
        Settlement negative = settlement(Faction.ALLIED_KINGDOM, OVERWORLD, -257, -257);
        Settlement low = settlement(Faction.ALLIED_KINGDOM, OVERWORLD, Integer.MIN_VALUE + 3, Integer.MIN_VALUE);
        Settlement high = settlement(Faction.ALLIED_KINGDOM, OVERWORLD, Integer.MAX_VALUE - 3, Integer.MAX_VALUE);
        data.add(negative);
        data.add(low);
        data.add(high);
        assertEquals(negative, data.nearestAllied(OVERWORLD, -256, -256, 2).orElseThrow());
        assertEquals(low, data.nearestAllied(OVERWORLD, Integer.MIN_VALUE, Integer.MIN_VALUE, 3).orElseThrow());
        assertEquals(high, data.nearestAllied(OVERWORLD, Integer.MAX_VALUE, Integer.MAX_VALUE, 3).orElseThrow());
        assertTrue(data.nearestAllied(OVERWORLD, 0, 0, 2).isEmpty());
    }

    @Test
    void emptyStoresAndInvalidSearchRangesHavePredictableResults() {
        SettlementSavedData data = new SettlementSavedData();
        assertTrue(data.nearestAllied(OVERWORLD, 0, 0, 4096).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> data.nearestAllied(OVERWORLD, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> data.nearestAllied(OVERWORLD, 0, 0, 4097));
        assertThrows(NullPointerException.class, () -> data.nearestAllied(null, 0, 0, 10));
    }
}

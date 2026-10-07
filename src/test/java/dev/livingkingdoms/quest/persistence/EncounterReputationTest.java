package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.domain.QuestTerms;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EncounterReputationTest {
    @TempDir Path directory;

    @Test void onePartyCannotRewardAnotherPlayerOrSettlementAgainAfterReload() {
        UUID party = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.awardEncounterReputationOnce(party, player, settlement, 4));
        assertEquals(4, data.reputation(player, settlement));
        assertEquals(QuestState.AVAILABLE, data.progress(player, settlement).state());
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        UUID otherPlayer = UUID.randomUUID(), otherSettlement = UUID.randomUUID();
        assertFalse(reopened.awardEncounterReputationOnce(party, player, settlement, 4));
        assertFalse(reopened.awardEncounterReputationOnce(party, otherPlayer, otherSettlement, 4));
        assertEquals(4, reopened.reputation(player, settlement));
        assertEquals(0, reopened.reputation(otherPlayer, otherSettlement));
        assertTrue(reopened.hasEncounterReward(party));
        assertFalse(reopened.isDirty());
    }

    @Test void questAndEncounterReputationAddWithoutChangingQuestCompletion() {
        UUID player = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.accept(player, settlement, new QuestTerms(16, 8, 10)));
        assertTrue(data.complete(player, settlement));
        assertTrue(data.awardEncounterReputationOnce(UUID.randomUUID(), player, settlement, 2));
        assertEquals(12, data.reputation(player, settlement));
        assertEquals(QuestState.COMPLETED, data.progress(player, settlement).state());
        assertFalse(data.complete(player, settlement));
    }

    @Test void schemaOneMigrationKeepsQuestsReputationAndMayors() {
        UUID player = UUID.randomUUID(), settlement = UUID.randomUUID(), mayor = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        data.accept(player, settlement, new QuestTerms(16, 8, 10));
        data.complete(player, settlement);
        data.associateMayor(settlement, mayor);
        CompoundTag legacy = data.save(new CompoundTag(), null);
        legacy.putInt("schema_version", 1);
        legacy.remove("encounter_rewards");
        QuestSavedData migrated = QuestSavedData.load(legacy, null);
        assertEquals(data.progress(player, settlement), migrated.progress(player, settlement));
        assertEquals(mayor, migrated.mayor(settlement).orElseThrow());
        assertTrue(migrated.awardEncounterReputationOnce(UUID.randomUUID(), player, settlement, 2));
        assertEquals(12, migrated.reputation(player, settlement));
        assertEquals(2, migrated.save(new CompoundTag(), null).getInt("schema_version"));
    }

    @Test void receiptAndReputationRoundTripThroughRealDiskStorage() {
        UUID party = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
        DimensionDataStorage storage = new DimensionDataStorage(directory.toFile(), null, null);
        QuestSavedData data = QuestSavedData.getOrCreate(storage, directory);
        data.awardEncounterReputationOnce(party, player, settlement, 2);
        storage.save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        DimensionDataStorage fresh = new DimensionDataStorage(directory.toFile(), null, null);
        QuestSavedData reopened = QuestSavedData.getOrCreate(fresh, directory);
        assertNotSame(data, reopened);
        assertEquals(2, reopened.reputation(player, settlement));
        assertTrue(reopened.hasEncounterReward(party));
        assertFalse(reopened.awardEncounterReputationOnce(party, player, settlement, 2));
    }

    @Test void malformedOrDuplicateReceiptsAreRejected() {
        UUID party = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        data.awardEncounterReputationOnce(party, player, settlement, 2);
        CompoundTag valid = data.save(new CompoundTag(), null);
        CompoundTag missing = valid.copy();
        missing.remove("encounter_rewards");
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(missing, null));
        CompoundTag duplicate = valid.copy();
        var receipts = duplicate.getList("encounter_rewards", Tag.TAG_COMPOUND);
        receipts.add(receipts.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(duplicate, null));
        CompoundTag invalid = valid.copy();
        invalid.getList("encounter_rewards", Tag.TAG_COMPOUND).getCompound(0).putInt("amount", -1);
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(invalid, null));
    }
}

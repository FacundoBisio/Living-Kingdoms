package dev.livingkingdoms.quest.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EncounterBatchRewardTest {
    @Test void multiplayerBatchSurvivesReloadAndCannotAppendLatePlayers() {
        UUID party = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.awardEncounterReputationOnce(party, Set.of(a, b), settlement, 4));
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(4, reopened.reputation(a, settlement));
        assertEquals(4, reopened.reputation(b, settlement));
        assertFalse(reopened.awardEncounterReputationOnce(party, Set.of(a, b, c), settlement, 4));
        assertEquals(0, reopened.reputation(c, settlement));
        assertFalse(reopened.isDirty());
    }

    @Test void schemaTwoSingleReceiptsMigrateAndStillBlockWholeParty() {
        UUID party = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        data.awardEncounterReputationOnce(party, player, settlement, 2);
        CompoundTag legacy = data.save(new CompoundTag(), null);
        legacy.putInt("schema_version", 2);
        CompoundTag receipt = legacy.getList("encounter_rewards", Tag.TAG_COMPOUND).getCompound(0);
        receipt.remove("players");
        receipt.putUUID("player", player);
        QuestSavedData migrated = QuestSavedData.load(legacy, null);
        assertEquals(2, migrated.reputation(player, settlement));
        assertFalse(migrated.awardEncounterReputationOnce(party, Set.of(UUID.randomUUID()), settlement, 4));
        assertEquals(4, migrated.save(new CompoundTag(), null).getInt("schema_version"));
    }

    @Test void cleanupRemovesOnlyReceiptsAndPreservesPlayerReputation() {
        UUID retired = UUID.randomUUID(), kept = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        data.awardEncounterReputationOnce(retired, player, settlement, 2);
        data.awardEncounterReputationOnce(kept, player, settlement, 4);
        data.setDirty(false);
        data.retainEncounterReceipts(Set.of(kept));
        assertTrue(data.isDirty());
        assertFalse(data.hasEncounterReward(retired));
        assertTrue(data.hasEncounterReward(kept));
        assertEquals(6, data.reputation(player, settlement));
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(6, reopened.reputation(player, settlement));
        assertFalse(reopened.hasEncounterReward(retired));
    }

    @Test void overflowingAnyParticipantMakesWholeBatchAtomic() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        data.awardEncounterReputationOnce(UUID.randomUUID(), a, settlement, 2);
        CompoundTag saved = data.save(new CompoundTag(), null);
        saved.getList("player_settlements", Tag.TAG_COMPOUND).getCompound(0).putInt("reputation", Integer.MAX_VALUE);
        QuestSavedData reopened = QuestSavedData.load(saved, null);
        UUID party = UUID.randomUUID();
        assertThrows(ArithmeticException.class, () -> reopened.awardEncounterReputationOnce(party, Set.of(a, b), settlement, 4));
        assertEquals(Integer.MAX_VALUE, reopened.reputation(a, settlement));
        assertEquals(0, reopened.reputation(b, settlement));
        assertFalse(reopened.hasEncounterReward(party));
        assertFalse(reopened.isDirty());
    }

    @Test void emptyUnknownAndDuplicateRecipientsCannotLoadOrMutateRewards() {
        UUID party = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
        QuestSavedData data = new QuestSavedData();
        assertThrows(IllegalArgumentException.class, () -> data.awardEncounterReputationOnce(party, Set.of(), settlement, 2));
        assertFalse(data.isDirty());
        data.awardEncounterReputationOnce(party, player, settlement, 2);
        CompoundTag valid = data.save(new CompoundTag(), null);
        CompoundTag duplicate = valid.copy();
        var recipients = duplicate.getList("encounter_rewards", Tag.TAG_COMPOUND).getCompound(0).getList("players", Tag.TAG_COMPOUND);
        recipients.add(recipients.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(duplicate, null));
        CompoundTag unknown = valid.copy();
        unknown.getList("encounter_rewards", Tag.TAG_COMPOUND).getCompound(0).getList("players", Tag.TAG_COMPOUND).getCompound(0).putUUID("player", UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(unknown, null));
        CompoundTag empty = valid.copy();
        empty.getList("encounter_rewards", Tag.TAG_COMPOUND).getCompound(0).put("players", new ListTag());
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(empty, null));
    }
}

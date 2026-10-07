package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.quest.domain.PlayerSettlementProgress;
import dev.livingkingdoms.quest.domain.QuestId;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.domain.QuestTerms;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class QuestSavedDataTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID settlement = UUID.randomUUID();
    private final QuestTerms terms = new QuestTerms(16, 8, 10);

    private DimensionDataStorage newStorage() {
        SharedConstants.tryDetectVersion();
        return new DimensionDataStorage(directory.toFile(), null, null);
    }

    private QuestSavedData activeData() {
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.accept(player, settlement, terms));
        return data;
    }

    private static CompoundTag firstRelationship(CompoundTag tag) {
        return tag.getList("player_settlements", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static CompoundTag firstQuest(CompoundTag tag) {
        return firstRelationship(tag).getList("quests", Tag.TAG_COMPOUND).getCompound(0);
    }

    @Test
    void newPlayerHasAvailableQuestAndZeroReputationWithoutDirtyingStorage() {
        QuestSavedData data = new QuestSavedData();
        assertEquals(new PlayerSettlementProgress(QuestState.AVAILABLE, null, 0),
                data.progress(player, settlement));
        assertEquals(0, data.reputation(player, settlement));
        assertTrue(data.mayor(settlement).isEmpty());
        assertFalse(data.complete(player, settlement));
        assertFalse(data.fail(player, settlement));
        assertFalse(data.isDirty());
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(data.progress(player, settlement), reopened.progress(player, settlement));
        assertFalse(reopened.isDirty());
    }

    @Test
    void twoPlayersAndTwoSettlementsHaveIndependentQuestStateAndReputation() {
        UUID secondPlayer = UUID.randomUUID();
        UUID secondSettlement = UUID.randomUUID();
        QuestSavedData data = activeData();
        assertTrue(data.accept(secondPlayer, settlement, terms));
        assertTrue(data.accept(player, secondSettlement, new QuestTerms(32, 12, 40)));
        assertTrue(data.complete(player, settlement));
        assertEquals(new PlayerSettlementProgress(QuestState.COMPLETED, terms, 10),
                data.progress(player, settlement));
        assertEquals(new PlayerSettlementProgress(QuestState.ACTIVE, terms, 0),
                data.progress(secondPlayer, settlement));
        assertEquals(QuestState.ACTIVE, data.progress(player, secondSettlement).state());
        assertEquals(0, data.progress(player, secondSettlement).reputation());
        assertTrue(data.complete(player, secondSettlement));
        assertEquals(40, data.reputation(player, secondSettlement));
        assertEquals(10, data.reputation(player, settlement));
        assertTrue(data.complete(secondPlayer, settlement));
        assertEquals(10, data.reputation(secondPlayer, settlement));
    }

    @Test
    void acceptanceSnapshotsTermsAndCompletionCanGrantReputationOnlyOnceIncludingAfterReload() {
        QuestSavedData data = activeData();
        PlayerSettlementProgress snapshot = data.progress(player, settlement);
        QuestTerms changedConfig = new QuestTerms(64, 32, 50);
        assertFalse(data.accept(player, settlement, changedConfig));
        assertEquals(terms, data.progress(player, settlement).terms());
        assertTrue(data.complete(player, settlement));
        assertEquals(QuestState.ACTIVE, snapshot.state());
        assertEquals(0, snapshot.reputation());
        assertFalse(data.complete(player, settlement));
        assertFalse(data.accept(player, settlement, changedConfig));
        assertFalse(data.fail(player, settlement));
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        assertFalse(reopened.complete(player, settlement));
        assertFalse(reopened.accept(player, settlement, changedConfig));
        assertEquals(new PlayerSettlementProgress(QuestState.COMPLETED, terms, 10),
                reopened.progress(player, settlement, QuestId.IRON_SHORTAGE));
        assertFalse(reopened.isDirty());
    }

    @Test
    void failedQuestIsTerminalAndGrantsNoReputation() {
        QuestSavedData data = activeData();
        assertTrue(data.fail(player, settlement));
        assertFalse(data.fail(player, settlement));
        assertFalse(data.complete(player, settlement));
        assertFalse(data.accept(player, settlement, terms));
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(new PlayerSettlementProgress(QuestState.FAILED, terms, 0),
                reopened.progress(player, settlement));
    }

    @Test
    void mayorAssociationIsUniqueIdempotentAndPersistent() {
        QuestSavedData data = new QuestSavedData();
        UUID mayor = UUID.randomUUID();
        UUID anotherSettlement = UUID.randomUUID();
        assertTrue(data.associateMayor(settlement, mayor));
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertTrue(data.associateMayor(settlement, mayor));
        assertFalse(data.associateMayor(settlement, UUID.randomUUID()));
        assertFalse(data.associateMayor(anotherSettlement, mayor));
        assertFalse(data.isDirty());
        QuestSavedData reopened = QuestSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(mayor, reopened.mayor(settlement).orElseThrow());
        assertTrue(reopened.mayor(anotherSettlement).isEmpty());
    }

    @Test
    void dimensionStorageSavesAndReopensQuestReputationTermsAndMayor() {
        DimensionDataStorage storage = newStorage();
        QuestSavedData data = QuestSavedData.getOrCreate(storage, directory);
        assertSame(data, QuestSavedData.getOrCreate(storage, directory));
        UUID activePlayer = UUID.randomUUID();
        UUID mayor = UUID.randomUUID();
        assertTrue(data.accept(player, settlement, terms));
        assertTrue(data.complete(player, settlement));
        assertTrue(data.accept(activePlayer, settlement, new QuestTerms(24, 6, 15)));
        assertTrue(data.associateMayor(settlement, mayor));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(Files.isRegularFile(directory.resolve(QuestSavedData.DATA_NAME + ".dat")));

        QuestSavedData reopened = QuestSavedData.getOrCreate(newStorage(), directory);
        assertNotSame(data, reopened);
        assertEquals(data.progress(player, settlement), reopened.progress(player, settlement));
        assertEquals(data.progress(activePlayer, settlement), reopened.progress(activePlayer, settlement));
        assertEquals(mayor, reopened.mayor(settlement).orElseThrow());
        assertFalse(reopened.complete(player, settlement));
        assertFalse(reopened.isDirty());
    }

    @Test
    void compressedNbtRoundTripRetainsSignedSettlementReputationAndAcceptedTerms() throws IOException {
        CompoundTag saved = activeData().save(new CompoundTag(), null);
        firstRelationship(saved).putInt("reputation", -5);
        QuestSavedData data = QuestSavedData.load(saved, null);
        assertEquals(-5, data.reputation(player, settlement));
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), null));
        Path file = directory.resolve(QuestSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        QuestSavedData reopened = QuestSavedData.load(
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), null);
        assertEquals(new PlayerSettlementProgress(QuestState.ACTIVE, terms, -5),
                reopened.progress(player, settlement));
        assertTrue(reopened.complete(player, settlement));
        assertEquals(5, reopened.reputation(player, settlement));
    }

    @Test
    void rejectsMalformedRelationshipAndQuestFields() {
        CompoundTag valid = activeData().save(new CompoundTag(), null);
        assertMalformed(valid, tag -> tag.putInt("schema_version", 5));
        assertMalformed(valid, tag -> tag.remove("schema_version"));
        assertMalformed(valid, tag -> tag.remove("mayors"));
        assertMalformed(valid, tag -> firstRelationship(tag).remove("player"));
        assertMalformed(valid, tag -> firstRelationship(tag).putString("settlement", settlement.toString()));
        assertMalformed(valid, tag -> firstRelationship(tag).remove("reputation"));
        assertMalformed(valid, tag -> firstRelationship(tag).remove("quests"));
        assertMalformed(valid, tag -> firstQuest(tag).putString("quest_id", "unknown_quest"));
        assertMalformed(valid, tag -> firstQuest(tag).putString("state", "REWARDED"));
        assertMalformed(valid, tag -> firstQuest(tag).remove("terms"));
        assertMalformed(valid, tag -> firstQuest(tag).getCompound("terms").remove("required_iron"));
        assertMalformed(valid, tag -> firstQuest(tag).getCompound("terms").putInt("reward_emeralds", 0));
        assertMalformed(valid, tag -> firstQuest(tag).putString("state", "AVAILABLE"));
        ListTag wrongType = new ListTag();
        wrongType.add(StringTag.valueOf("invalid"));
        assertMalformed(valid, tag -> tag.put("player_settlements", wrongType));
    }

    @Test
    void rejectsDuplicateRelationshipsQuestsAndMayorAssociations() {
        CompoundTag valid = activeData().save(new CompoundTag(), null);
        assertMalformed(valid, tag -> tag.getList("player_settlements", Tag.TAG_COMPOUND)
                .add(firstRelationship(tag).copy()));
        assertMalformed(valid, tag -> firstRelationship(tag).getList("quests", Tag.TAG_COMPOUND)
                .add(firstQuest(tag).copy()));
        QuestSavedData mayorData = activeData();
        mayorData.associateMayor(settlement, UUID.randomUUID());
        CompoundTag withMayor = mayorData.save(new CompoundTag(), null);
        assertMalformed(withMayor, tag -> tag.getList("mayors", Tag.TAG_COMPOUND)
                .add(tag.getList("mayors", Tag.TAG_COMPOUND).getCompound(0).copy()));
        assertMalformed(withMayor, tag -> {
            ListTag mayors = tag.getList("mayors", Tag.TAG_COMPOUND);
            CompoundTag duplicateEntity = mayors.getCompound(0).copy();
            duplicateEntity.putUUID("settlement", UUID.randomUUID());
            mayors.add(duplicateEntity);
        });
    }

    private static void assertMalformed(CompoundTag valid, Consumer<CompoundTag> mutation) {
        CompoundTag invalid = valid.copy();
        mutation.accept(invalid);
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(invalid, null));
    }

    @Test
    void dimensionStorageRefusesUnknownSchemaWithoutOverwritingFile() throws IOException {
        CompoundTag unknown = activeData().save(new CompoundTag(), null);
        unknown.putInt("schema_version", 5);
        writeAndAssertProtected(unknown);
    }

    @Test
    void dimensionStorageRefusesMalformedRecordsWithoutOverwritingFile() throws IOException {
        CompoundTag malformed = activeData().save(new CompoundTag(), null);
        firstQuest(malformed).remove("terms");
        writeAndAssertProtected(malformed);
    }

    private void writeAndAssertProtected(CompoundTag data) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        Path file = directory.resolve(QuestSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        assertUnloadedFileIsPreserved(file);
    }

    @Test
    void dimensionStorageRefusesTruncatedFileWithoutOverwritingIt() throws IOException {
        Path file = directory.resolve(QuestSavedData.DATA_NAME + ".dat");
        Files.write(file, new byte[]{0x1f, (byte) 0x8b, 0x08});
        assertUnloadedFileIsPreserved(file);
    }

    private void assertUnloadedFileIsPreserved(Path file) throws IOException {
        byte[] original = Files.readAllBytes(file);
        DimensionDataStorage storage = newStorage();
        assertThrows(IllegalStateException.class, () -> QuestSavedData.getOrCreate(storage, directory));
        assertThrows(IllegalStateException.class, () -> QuestSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}

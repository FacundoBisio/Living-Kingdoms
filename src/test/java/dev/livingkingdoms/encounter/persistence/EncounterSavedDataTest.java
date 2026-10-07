package dev.livingkingdoms.encounter.persistence;

import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EncounterSavedDataTest {
    @TempDir Path directory;

    private static HostileParty party(PartyType type, String dimension, Set<UUID> members) {
        return new HostileParty(UUID.randomUUID(), type.faction(), type,
                new OriginRegion(dimension, 234, 68, -512, 32), UUID.randomUUID(), PartyState.ALIVE,
                members, members, 12, 4, true, false);
    }

    private DimensionDataStorage newStorage() {
        SharedConstants.tryDetectVersion();
        return new DimensionDataStorage(directory.toFile(), null, null);
    }

    private static CompoundTag firstParty(CompoundTag tag) {
        return tag.getList("parties", Tag.TAG_COMPOUND).getCompound(0);
    }

    @Test
    void recordDeathDetectsOnlyFirstFullDefeatAndDoesNotRewardUnrelatedMobs() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        HostileParty party = party(PartyType.PILLAGER_PATROL, "minecraft:overworld", Set.of(first, second));
        EncounterSavedData data = new EncounterSavedData();
        data.add(party);
        data.setDirty(false);
        assertTrue(data.recordDeath(UUID.randomUUID()).isEmpty());
        assertFalse(data.isDirty());
        assertTrue(data.recordDeath(first).isEmpty());
        assertTrue(data.isDirty());
        assertEquals(Set.of(second), data.get(party.id()).orElseThrow().remainingMembers());
        data.setDirty(false);
        assertTrue(data.recordDeath(first).isEmpty());
        assertFalse(data.isDirty());
        HostileParty defeated = data.recordDeath(second).orElseThrow();
        assertEquals(PartyState.DEFEATED, defeated.state());
        assertEquals(party.id(), defeated.id());
        assertEquals(defeated, data.forMember(first).orElseThrow());
        data.setDirty(false);
        assertTrue(data.recordDeath(first).isEmpty());
        assertTrue(data.recordDeath(second).isEmpty());
        assertFalse(data.isDirty());
    }

    @Test
    void partialDefeatReloadRetainsDeadRosterAndFinalTransitionOccursOnce() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        HostileParty party = party(PartyType.UNDEAD_HORDE, "minecraft:overworld", Set.of(first, second));
        EncounterSavedData data = new EncounterSavedData();
        data.add(party);
        assertTrue(data.recordDeath(first).isEmpty());
        EncounterSavedData reopened = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        assertFalse(reopened.isDirty());
        assertTrue(reopened.recordDeath(first).isEmpty());
        assertFalse(reopened.isDirty());
        assertEquals(Set.of(second), reopened.forMember(second).orElseThrow().remainingMembers());
        assertTrue(reopened.recordDeath(second).isPresent());
        EncounterSavedData defeatedReload = EncounterSavedData.load(reopened.save(new CompoundTag(), null), null);
        assertTrue(defeatedReload.recordDeath(second).isEmpty());
        assertEquals(PartyState.DEFEATED, defeatedReload.get(party.id()).orElseThrow().state());
        assertFalse(defeatedReload.isDirty());
    }

    @Test
    void storageRejectsDuplicatePartyAndMemberIdentitiesWithoutPartialMutation() {
        UUID member = UUID.randomUUID();
        HostileParty first = party(PartyType.PILLAGER_PATROL, "minecraft:overworld", Set.of(member));
        EncounterSavedData data = new EncounterSavedData();
        data.add(first);
        data.setDirty(false);
        assertThrows(IllegalArgumentException.class, () -> data.add(first));
        HostileParty duplicateMember = party(PartyType.UNDEAD_HORDE, "minecraft:the_nether", Set.of(member));
        assertThrows(IllegalArgumentException.class, () -> data.add(duplicateMember));
        assertEquals(List.of(first), data.parties());
        assertFalse(data.isDirty());
        assertThrows(UnsupportedOperationException.class, () -> data.parties().clear());
        data.recordDeath(member);
        assertThrows(IllegalArgumentException.class, () -> data.add(duplicateMember));
    }

    @Test
    void memberConversionsUpdateIndexAndSurviveReloadWithoutCompletingTheParty() {
        UUID zombie = UUID.randomUUID();
        UUID drowned = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        HostileParty undead = party(PartyType.UNDEAD_HORDE, "minecraft:overworld", Set.of(zombie));
        EncounterSavedData data = new EncounterSavedData();
        data.add(undead);
        data.add(party(PartyType.PILLAGER_PATROL, "minecraft:overworld", Set.of(other)));
        data.setDirty(false);
        assertFalse(data.replaceMember(zombie, other));
        assertFalse(data.replaceMember(UUID.randomUUID(), drowned));
        assertFalse(data.isDirty());
        assertTrue(data.replaceMember(zombie, zombie));
        assertFalse(data.isDirty());
        assertTrue(data.replaceMember(zombie, drowned));
        assertTrue(data.isDirty());
        assertTrue(data.forMember(zombie).isEmpty());
        assertEquals(undead.id(), data.forMember(drowned).orElseThrow().id());
        assertEquals(PartyState.ALIVE, data.forMember(drowned).orElseThrow().state());
        EncounterSavedData reopened = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(reopened.recordDeath(zombie).isEmpty());
        assertTrue(reopened.recordDeath(drowned).isPresent());
        assertFalse(reopened.replaceMember(drowned, UUID.randomUUID()));
    }

    @Test
    void dimensionStorageSavesAndReopensAllFieldsForIndependentFactions() {
        DimensionDataStorage storage = newStorage();
        EncounterSavedData data = EncounterSavedData.getOrCreate(storage, directory);
        assertSame(data, EncounterSavedData.getOrCreate(storage, directory));
        UUID firstMember = UUID.randomUUID();
        HostileParty pillagers = party(PartyType.PILLAGER_PATROL, "minecraft:overworld", Set.of(firstMember));
        HostileParty undead = party(PartyType.UNDEAD_HORDE, "minecraft:the_nether", Set.of(UUID.randomUUID()));
        data.add(pillagers);
        data.add(undead);
        data.recordDeath(firstMember);
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(Files.isRegularFile(directory.resolve(EncounterSavedData.DATA_NAME + ".dat")));
        EncounterSavedData reopened = EncounterSavedData.getOrCreate(newStorage(), directory);
        assertNotSame(data, reopened);
        assertEquals(data.parties(), reopened.parties());
        assertFalse(reopened.isDirty());
        assertTrue(reopened.recordDeath(firstMember).isEmpty());
        assertEquals(PartyType.UNDEAD_HORDE, reopened.forMember(undead.memberIds().iterator().next()).orElseThrow().type());
    }

    @Test
    void compressedRoundTripPreservesExplicitRewardEligibilityAndUnassociatedOrigin() throws IOException {
        UUID member = UUID.randomUUID();
        HostileParty party = new HostileParty(UUID.randomUUID(), PartyType.UNDEAD_HORDE.faction(),
                PartyType.UNDEAD_HORDE, new OriginRegion("minecraft:the_end", -32, 91, 44, 64),
                null, PartyState.ALIVE, Set.of(member), Set.of(member), 35, 2, true, true);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party);
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), null));
        Path file = directory.resolve(EncounterSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        EncounterSavedData reopened = EncounterSavedData.load(
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), null);
        assertEquals(List.of(party), reopened.parties());
        assertEquals(party, reopened.forMember(member).orElseThrow());
        assertFalse(reopened.isDirty());
    }

    @Test
    void strictNbtRejectsMalformedRewardIdentityRosterAndState() {
        EncounterSavedData data = new EncounterSavedData();
        data.add(party(PartyType.PILLAGER_PATROL, "minecraft:overworld", Set.of(UUID.randomUUID())));
        CompoundTag valid = data.save(new CompoundTag(), null);
        assertMalformed(valid, tag -> tag.remove("schema_version"));
        assertMalformed(valid, tag -> tag.putInt("schema_version", 3));
        assertMalformed(valid, tag -> tag.remove("parties"));
        assertMalformed(valid, tag -> firstParty(tag).remove("id"));
        assertMalformed(valid, tag -> firstParty(tag).putString("associated_settlement", "invalid"));
        assertMalformed(valid, tag -> firstParty(tag).putString("faction", "unknown"));
        assertMalformed(valid, tag -> firstParty(tag).putString("faction", "undead"));
        assertMalformed(valid, tag -> firstParty(tag).putString("type", "bandit_patrol"));
        assertMalformed(valid, tag -> firstParty(tag).putString("state", "REWARDED"));
        assertMalformed(valid, tag -> firstParty(tag).putString("state", "DEFEATED"));
        assertMalformed(valid, tag -> firstParty(tag).putInt("threat_rating", 0));
        assertMalformed(valid, tag -> firstParty(tag).putInt("reputation_reward", -1));
        assertMalformed(valid, tag -> firstParty(tag).remove("debug"));
        assertMalformed(valid, tag -> firstParty(tag).putByte("reward_eligible", (byte) 2));
        assertMalformed(valid, tag -> firstParty(tag).getCompound("origin").putInt("radius", 0));
        assertMalformed(valid, tag -> firstParty(tag).put("members", new ListTag()));
        assertMalformed(valid, tag -> firstParty(tag).put("remaining_members", new ListTag()));
        assertMalformed(valid, tag -> {
            ListTag list = new ListTag();
            list.add(StringTag.valueOf("invalid"));
            firstParty(tag).put("members", list);
        });
        assertMalformed(valid, tag -> {
            ListTag members = firstParty(tag).getList("members", Tag.TAG_INT_ARRAY);
            members.add(members.get(0).copy());
        });
        assertMalformed(valid, tag -> tag.getList("parties", Tag.TAG_COMPOUND).add(firstParty(tag).copy()));
        assertMalformed(valid, tag -> {
            CompoundTag other = firstParty(tag).copy();
            other.putUUID("id", UUID.randomUUID());
            tag.getList("parties", Tag.TAG_COMPOUND).add(other);
        });
    }

    private static void assertMalformed(CompoundTag valid, Consumer<CompoundTag> mutation) {
        CompoundTag invalid = valid.copy();
        mutation.accept(invalid);
        assertThrows(IllegalArgumentException.class, () -> EncounterSavedData.load(invalid, null));
    }

    @Test
    void emptyNewStorageLoadsCleanly() {
        EncounterSavedData data = new EncounterSavedData();
        EncounterSavedData reopened = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(reopened.parties().isEmpty());
        assertTrue(reopened.forMember(UUID.randomUUID()).isEmpty());
        assertTrue(reopened.get(UUID.randomUUID()).isEmpty());
        assertFalse(reopened.isDirty());
    }

    @Test
    void dimensionStorageRefusesFutureSchemaWithoutOverwritingIt() throws IOException {
        CompoundTag future = new EncounterSavedData().save(new CompoundTag(), null);
        future.putInt("schema_version", 3);
        writeAndAssertProtected(future);
    }

    @Test
    void dimensionStorageRefusesMalformedRecordsWithoutOverwritingThem() throws IOException {
        CompoundTag corrupt = new EncounterSavedData().save(new CompoundTag(), null);
        ListTag parties = new ListTag();
        parties.add(new CompoundTag());
        corrupt.put("parties", parties);
        writeAndAssertProtected(corrupt);
    }

    private void writeAndAssertProtected(CompoundTag data) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        Path file = directory.resolve(EncounterSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        assertUnloadedFileIsPreserved(file);
    }

    @Test
    void dimensionStorageRefusesTruncatedFileWithoutOverwritingIt() throws IOException {
        Path file = directory.resolve(EncounterSavedData.DATA_NAME + ".dat");
        Files.write(file, new byte[]{0x1f, (byte) 0x8b, 0x08});
        assertUnloadedFileIsPreserved(file);
    }

    private void assertUnloadedFileIsPreserved(Path file) throws IOException {
        byte[] original = Files.readAllBytes(file);
        DimensionDataStorage storage = newStorage();
        assertThrows(IllegalStateException.class, () -> EncounterSavedData.getOrCreate(storage, directory));
        assertThrows(IllegalStateException.class, () -> EncounterSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}

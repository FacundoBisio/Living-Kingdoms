package dev.livingkingdoms.encounter.persistence;

import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EncounterLifecycleTest {
    @TempDir Path directory;

    private static HostileParty party(String dimension, int x, int z, PartyType type, UUID... members) {
        Set<UUID> roster = Set.of(members);
        return new HostileParty(UUID.randomUUID(), type.faction(), type,
                new OriginRegion(dimension, x, 64, z, 8), null, PartyState.ALIVE,
                roster, roster, 3, 2, false, true);
    }

    private static HostileParty party(UUID... members) {
        return party("minecraft:overworld", 0, 0, PartyType.PILLAGER_PATROL, members);
    }

    private static CompoundTag firstParty(CompoundTag tag) {
        return tag.getList("parties", Tag.TAG_COMPOUND).getCompound(0);
    }

    @Test
    void meaningfulDamageAccumulatesAcrossMembersAndCreditsEachPlayerOnce() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        HostileParty party = party(first, second);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 10);
        assertTrue(data.recordContribution(first, alice, 2, 20));
        assertTrue(data.recordContribution(second, alice, 2, 21));
        assertTrue(data.recordContribution(second, bob, 6, 21));
        assertEquals(Set.of(alice, bob), data.eligibleParticipants(party.id(), 21, 4, 6000));
        assertThrows(UnsupportedOperationException.class,
                () -> data.eligibleParticipants(party.id(), 21, 4, 6000).clear());
        data.recordDeath(first, 22);
        assertTrue(data.recordDeath(second, 23).isPresent());
        assertEquals(Set.of(alice, bob), data.eligibleParticipants(party.id(), 23, 4, 6000));
        assertFalse(data.recordContribution(second, UUID.randomUUID(), 8, 24));
    }

    @Test
    void unrelatedDeadBlockedNonfiniteAndNegativeDamageDoNotCreateParticipation() {
        UUID member = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HostileParty party = party(member, UUID.randomUUID());
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 1);
        data.setDirty(false);
        assertFalse(data.recordContribution(UUID.randomUUID(), player, 10, 2));
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertFalse(data.recordContribution(member, player, invalid, 2));
        }
        assertFalse(data.isDirty());
        assertThrows(IllegalArgumentException.class, () -> data.recordContribution(member, player, 1, -1));
        data.recordDeath(member, 2);
        data.setDirty(false);
        assertFalse(data.recordContribution(member, player, 10, 3));
        assertFalse(data.isDirty());
        assertTrue(data.eligibleParticipants(party.id(), 3, 4, 6000).isEmpty());
    }

    @Test
    void inactiveDamageExpiresAndNewContributionStartsAgain() {
        UUID member = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HostileParty party = party(member);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 0);
        data.recordContribution(member, player, 4, 10);
        assertEquals(Set.of(player), data.eligibleParticipants(party.id(), 6010, 4, 6000));
        assertTrue(data.eligibleParticipants(party.id(), 6011, 4, 6000).isEmpty());
        data.recordContribution(member, player, 1, 6011);
        assertTrue(data.eligibleParticipants(party.id(), 6011, 4, 6000).isEmpty());
        data.recordContribution(member, player, 3, 6012);
        assertEquals(Set.of(player), data.eligibleParticipants(party.id(), 6012, 4, 6000));
        assertTrue(data.eligibleParticipants(party.id(), 6010, 4, 6000).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> data.eligibleParticipants(party.id(), -1, 4, 6000));
        assertThrows(IllegalArgumentException.class, () -> data.eligibleParticipants(party.id(), 6012, Double.NaN, 6000));
        assertThrows(IllegalArgumentException.class, () -> data.eligibleParticipants(party.id(), 6012, 4, -1));
    }

    @Test
    void participantCapIsBoundedAndExpiredSlotsCanBeReused() {
        UUID member = UUID.randomUUID();
        HostileParty party = party(member);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 0);
        UUID first = UUID.randomUUID();
        data.recordContribution(member, first, Float.MAX_VALUE, 10);
        for (int i = 1; i < EncounterSavedData.MAX_PARTICIPANTS; i++) {
            assertTrue(data.recordContribution(member, UUID.randomUUID(), 4, 10));
        }
        assertEquals(64, data.eligibleParticipants(party.id(), 10, 4, 6000).size());
        assertTrue(data.recordContribution(member, first, Float.MAX_VALUE, 10));
        assertFalse(data.recordContribution(member, UUID.randomUUID(), 4, 10));
        assertEquals(1_000_000, firstParty(data.save(new CompoundTag(), null)).getCompound("tracking")
                .getList("participants", Tag.TAG_COMPOUND).getCompound(0).getDouble("damage"));
        UUID newcomer = UUID.randomUUID();
        assertTrue(data.recordContribution(member, newcomer, 4, 6011));
        assertEquals(Set.of(newcomer), data.eligibleParticipants(party.id(), 6011, 4, 6000));
    }

    @Test
    void regionalLookupHandlesBucketBoundariesDimensionsInclusiveDistanceAndExtremes() {
        EncounterSavedData data = new EncounterSavedData();
        HostileParty west = party("minecraft:overworld", -256, -1, PartyType.PILLAGER_PATROL, UUID.randomUUID());
        HostileParty east = party("minecraft:overworld", 256, -1, PartyType.UNDEAD_HORDE, UUID.randomUUID());
        HostileParty distant = party("minecraft:overworld", 257, -1, PartyType.PILLAGER_PATROL, UUID.randomUUID());
        HostileParty other = party("minecraft:the_nether", 0, -1, PartyType.UNDEAD_HORDE, UUID.randomUUID());
        HostileParty extreme = party("minecraft:overworld", Integer.MAX_VALUE, Integer.MIN_VALUE,
                PartyType.PILLAGER_PATROL, UUID.randomUUID());
        for (HostileParty party : List.of(west, east, distant, other, extreme)) data.add(party, 0);
        assertEquals(Set.of(west, east), Set.copyOf(data.activeNearby("minecraft:overworld", 0, -1, 256)));
        assertEquals(List.of(other), data.activeNearby("minecraft:the_nether", 0, -1, 0));
        assertEquals(List.of(extreme), data.activeNearby("minecraft:overworld", Integer.MAX_VALUE, Integer.MIN_VALUE, 4096));
        assertTrue(data.activeNearby("minecraft:overworld", Integer.MIN_VALUE, Integer.MAX_VALUE, 4096).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> data.activeNearby("minecraft:overworld", 0, -1, 256).clear());
        assertThrows(IllegalArgumentException.class, () -> data.activeNearby("minecraft:overworld", 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> data.activeNearby("minecraft:overworld", 0, 0, 4097));
    }

    @Test
    void defeatImmediatelyFreesRegionalLimitButRetainsDedupUntilRetirement() {
        UUID member = UUID.randomUUID();
        HostileParty party = party(member);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 10);
        assertEquals(List.of(party), data.activeNearby("minecraft:overworld", 0, 0, 0));
        data.recordDeath(member, 20);
        assertTrue(data.activeNearby("minecraft:overworld", 0, 0, 4096).isEmpty());
        assertEquals(1, data.trackedPartyCount());
        assertTrue(data.recordDeath(member, 21).isEmpty());
        assertTrue(data.cleanup(119, 100, 1000).isEmpty());
        HostileParty removed = data.cleanup(120, 100, 1000).getFirst();
        assertEquals(PartyState.DEFEATED, removed.state());
        assertEquals(party.id(), removed.id());
        assertEquals(0, data.trackedPartyCount());
        assertTrue(data.forMember(member).isEmpty());
        assertTrue(data.recordDeath(member, 121).isEmpty());
        assertFalse(data.recordContribution(member, UUID.randomUUID(), 10, 121));
    }

    @Test
    void activeExpiryIsAbandonmentAndExplicitRemovalPurgesEveryIndex() {
        UUID member = UUID.randomUUID();
        HostileParty party = party(member);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 10);
        assertTrue(data.cleanup(109, 100, 100).isEmpty());
        assertEquals(List.of(party), data.cleanup(110, 100, 100));
        assertEquals(PartyState.ALIVE, party.state());
        assertTrue(data.activeNearby("minecraft:overworld", 0, 0, 0).isEmpty());
        assertTrue(data.forMember(member).isEmpty());
        data.add(party, 200);
        assertEquals(party, data.remove(party.id()).orElseThrow());
        data.setDirty(false);
        assertTrue(data.remove(party.id()).isEmpty());
        assertFalse(data.isDirty());
        assertEquals(0, data.trackedPartyCount());
        assertTrue(data.cleanup(300, 100, 100).isEmpty());
    }

    @Test
    void schemaOneMigrationKeepsPartiesAndStartsLegacyLifetimeConservatively() {
        UUID aliveMember = UUID.randomUUID();
        UUID deadMember = UUID.randomUUID();
        HostileParty alive = party(aliveMember);
        HostileParty defeated = party(deadMember).withMemberDeath(deadMember);
        EncounterSavedData original = new EncounterSavedData();
        original.add(alive);
        original.add(defeated);
        CompoundTag legacy = original.save(new CompoundTag(), null);
        legacy.putInt("schema_version", 1);
        legacy.remove("natural_schedule");
        for (Tag party : legacy.getList("parties", Tag.TAG_COMPOUND)) ((CompoundTag) party).remove("tracking");
        EncounterSavedData migrated = EncounterSavedData.load(legacy, null);
        assertEquals(List.of(alive, defeated), migrated.parties());
        assertFalse(migrated.isDirty());
        assertTrue(migrated.cleanup(50_000, 100, 1000).isEmpty());
        assertTrue(migrated.isDirty());
        assertEquals(List.of(alive), migrated.activeNearby("minecraft:overworld", 0, 0, 0));
        EncounterSavedData reopened = EncounterSavedData.load(migrated.save(new CompoundTag(), null), null);
        assertEquals(List.of(defeated), reopened.cleanup(50_100, 100, 1000));
        assertEquals(List.of(alive), reopened.cleanup(51_000, 100, 1000));
    }

    @Test
    void legacyDeathBeforeFirstCleanupProducesConsistentReloadableTimestamps() {
        UUID member = UUID.randomUUID();
        EncounterSavedData data = new EncounterSavedData();
        data.add(party(member));
        data.recordDeath(member, 100);
        assertTrue(data.cleanup(200, 1000, 1000).isEmpty());
        EncounterSavedData reopened = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(reopened.cleanup(1099, 1000, 1000).isEmpty());
        assertEquals(1, reopened.cleanup(1100, 1000, 1000).size());
    }

    @Test
    void compressedReloadPreservesParticipationCooldownAndRebuildsRegionalIndex() throws IOException {
        UUID member = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HostileParty party = party(member);
        EncounterSavedData data = new EncounterSavedData();
        data.add(party, 10);
        data.recordContribution(member, player, 4, 20);
        data.scheduleNaturalAttempt("minecraft:overworld", 6000);
        data.scheduleNaturalAttempt("minecraft:the_nether", 9000);
        Path file = directory.resolve("encounters.dat");
        NbtIo.writeCompressed(data.save(new CompoundTag(), null), file);
        EncounterSavedData reopened = EncounterSavedData.load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), null);
        assertEquals(List.of(party), reopened.activeNearby("minecraft:overworld", 0, 0, 0));
        assertEquals(Set.of(player), reopened.eligibleParticipants(party.id(), 21, 4, 6000));
        assertEquals(6000, reopened.nextNaturalAttempt("minecraft:overworld"));
        assertEquals(9000, reopened.nextNaturalAttempt("minecraft:the_nether"));
        assertEquals(0, reopened.nextNaturalAttempt("minecraft:the_end"));
        assertFalse(reopened.isDirty());
        reopened.scheduleNaturalAttempt("minecraft:overworld", 6000);
        assertFalse(reopened.isDirty());
        reopened.scheduleNaturalAttempt("minecraft:overworld", 7000);
        assertTrue(reopened.isDirty());
        assertThrows(IllegalArgumentException.class, () -> reopened.scheduleNaturalAttempt("bad dimension", 10));
        assertThrows(IllegalArgumentException.class, () -> reopened.scheduleNaturalAttempt("minecraft:overworld", -1));
    }

    @Test
    void schemaTwoStrictlyRejectsInvalidLifecycleParticipationAndSchedule() {
        UUID member = UUID.randomUUID();
        EncounterSavedData data = new EncounterSavedData();
        data.add(party(member), 10);
        data.recordContribution(member, UUID.randomUUID(), 4, 20);
        data.scheduleNaturalAttempt("minecraft:overworld", 1000);
        CompoundTag valid = data.save(new CompoundTag(), null);
        assertMalformed(valid, tag -> firstParty(tag).remove("tracking"));
        assertMalformed(valid, tag -> firstParty(tag).getCompound("tracking").putLong("created_at", -2));
        assertMalformed(valid, tag -> firstParty(tag).getCompound("tracking").putLong("finished_at", 11));
        assertMalformed(valid, tag -> firstParty(tag).getCompound("tracking").remove("participants"));
        assertMalformed(valid, tag -> participant(tag).remove("player"));
        assertMalformed(valid, tag -> participant(tag).putDouble("damage", Double.NaN));
        assertMalformed(valid, tag -> participant(tag).putDouble("damage", 0));
        assertMalformed(valid, tag -> participant(tag).putDouble("damage", 1_000_001));
        assertMalformed(valid, tag -> participant(tag).putLong("last_damage_tick", -1));
        assertMalformed(valid, tag -> {
            ListTag participants = firstParty(tag).getCompound("tracking").getList("participants", Tag.TAG_COMPOUND);
            participants.add(participants.get(0).copy());
        });
        assertMalformed(valid, tag -> {
            ListTag participants = firstParty(tag).getCompound("tracking").getList("participants", Tag.TAG_COMPOUND);
            for (int i = 1; i <= 64; i++) {
                CompoundTag extra = participant(tag).copy();
                extra.putUUID("player", UUID.randomUUID());
                participants.add(extra);
            }
        });
        assertMalformed(valid, tag -> tag.remove("natural_schedule"));
        assertMalformed(valid, tag -> tag.getList("natural_schedule", Tag.TAG_COMPOUND).getCompound(0).putString("dimension", "invalid"));
        assertMalformed(valid, tag -> tag.getList("natural_schedule", Tag.TAG_COMPOUND).getCompound(0).putLong("next_tick", -1));
        assertMalformed(valid, tag -> {
            ListTag schedule = tag.getList("natural_schedule", Tag.TAG_COMPOUND);
            schedule.add(schedule.get(0).copy());
        });
        assertMalformed(valid, tag -> {
            ListTag schedule = new ListTag();
            schedule.add(StringTag.valueOf("bad"));
            tag.put("natural_schedule", schedule);
        });
    }

    private static CompoundTag participant(CompoundTag tag) {
        return firstParty(tag).getCompound("tracking").getList("participants", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static void assertMalformed(CompoundTag valid, Consumer<CompoundTag> mutation) {
        CompoundTag invalid = valid.copy();
        mutation.accept(invalid);
        assertThrows(IllegalArgumentException.class, () -> EncounterSavedData.load(invalid, null));
    }
}

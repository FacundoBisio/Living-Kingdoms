package dev.livingkingdoms.defense.persistence;

import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.faction.Faction;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class DefenseSavedDataTest {
    @TempDir Path directory;
    private static SettlementThreatEvent event(UUID settlement, UUID party, Faction faction, long now, boolean rewardEligible) {
        return new SettlementThreatEvent(UUID.randomUUID(), settlement, "minecraft:overworld", faction, party, 15, 3,
                5, 5, ThreatState.DETECTED, ThreatOutcome.NONE, now, -1, -1, now + 1200, !rewardEligible, rewardEligible, Set.of());
    }
    private static SettlementThreatEvent event() { return event(UUID.randomUUID(), UUID.randomUUID(), Faction.PILLAGER, 100, true); }
    private static DefenseSavedData reload(DefenseSavedData data) { return DefenseSavedData.load(data.save(new CompoundTag(), null), null); }
    private static void victory(DefenseSavedData data, SettlementThreatEvent e, Set<UUID> players) {
        assertTrue(data.detect(e)); assertTrue(data.activate(e.id(), e.detectedAt()));
        assertTrue(data.updateProgress(e.id(), 0)); assertTrue(data.resolve(e.id(), e.detectedAt() + 100, players));
    }
    @Test void oneSharedThreatPerSettlementAndOneEventPerPartyAcrossReload() {
        var data = new DefenseSavedData(); var a = event(); assertTrue(data.detect(a)); assertFalse(data.detect(a));
        assertFalse(data.detect(event(a.settlementId(), UUID.randomUUID(), Faction.UNDEAD, 100, true)));
        assertFalse(data.detect(event(UUID.randomUUID(), a.partyId(), Faction.PILLAGER, 100, true)));
        assertEquals(a, data.active(a.settlementId()).orElseThrow()); assertEquals(a, data.forParty(a.partyId()).orElseThrow());
        var loaded = reload(data); assertFalse(loaded.detect(event(UUID.randomUUID(), a.partyId(), Faction.PILLAGER, 200, true)));
        assertEquals(List.of(a), loaded.activeEvents()); assertFalse(loaded.isDirty());
    }
    @Test void sameTickEventsKeepCreationOrderBeforeAndAfterReloadAndPruning() {
        var data=new DefenseSavedData(); UUID settlement=UUID.randomUUID();
        var first=event(settlement,UUID.randomUUID(),Faction.PILLAGER,100,true);
        var second=event(settlement,UUID.randomUUID(),Faction.UNDEAD,100,true);
        victory(data,first,Set.of()); victory(data,second,Set.of());
        assertEquals(second.id(),data.latest(settlement).orElseThrow().id());
        var loaded=reload(data); assertEquals(second.id(),loaded.latest(settlement).orElseThrow().id());
        assertTrue(loaded.removeTerminal(second.id())); assertEquals(first.id(),loaded.latest(settlement).orElseThrow().id());
    }
    @Test void allInitialHostileFactionsAndIndependentSettlementEventsPersist() {
        var data = new DefenseSavedData(); var events = new ArrayList<SettlementThreatEvent>();
        for (var faction : List.of(Faction.PILLAGER, Faction.BANDIT, Faction.UNDEAD)) {
            var e = event(UUID.randomUUID(), UUID.randomUUID(), faction, 100, true); events.add(e);
            assertTrue(data.detect(e)); assertTrue(data.activate(e.id(), 110)); assertTrue(data.updateProgress(e.id(), 3));
        }
        var loaded = reload(data); assertEquals(3, loaded.activeEvents().size());
        for (var e : events) {
            var restored = loaded.forParty(e.partyId()).orElseThrow(); assertEquals(e.faction(), restored.faction());
            assertEquals(3, restored.remainingMembers()); assertEquals(2, restored.defeatedMembers());
            assertEquals(e.settlementId(), restored.settlementId()); assertEquals(110, restored.startedAt());
        }
    }
    @Test void progressReplaysStaleReceiptsAndWrongPartyCannotResurrectOrDoubleResolve() {
        var data = new DefenseSavedData(); var e = event(); data.detect(e); data.activate(e.id(), 100);
        var receipt = data.get(e.id()).orElseThrow(); assertTrue(data.replace(receipt, receipt.withProgress(2)));
        assertFalse(data.replace(receipt, receipt.withProgress(1))); assertFalse(data.updateProgress(UUID.randomUUID(), 0));
        assertFalse(data.updateProgress(e.id(), 4)); assertFalse(data.resolve(e.id(), 200, Set.of(UUID.randomUUID())));
        assertTrue(data.updateProgress(e.id(), 0)); var player = UUID.randomUUID(); assertTrue(data.resolve(e.id(), 200, Set.of(player)));
        assertFalse(data.resolve(e.id(), 201, Set.of(player))); assertFalse(data.updateProgress(e.id(), 0));
        var loaded = reload(data); assertFalse(loaded.resolve(e.id(), 202, Set.of(player))); assertTrue(loaded.hasSuccessfulParticipation(player));
        assertTrue(loaded.active(e.settlementId()).isEmpty()); assertEquals(ThreatState.RESOLVED, loaded.latest(e.settlementId()).orElseThrow().state());
        assertFalse(loaded.detect(event(e.settlementId(), e.partyId(), Faction.PILLAGER, 300, true)));
    }
    @Test void multiplayerQualificationIsIndividualImmutableAndSurvivesReload() {
        var data = new DefenseSavedData(); var e = event(); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), spectator = UUID.randomUUID();
        var participants = new HashSet<>(Set.of(a, b)); victory(data, e, participants); participants.clear();
        var loaded = reload(data); var resolved = loaded.get(e.id()).orElseThrow(); assertEquals(Set.of(a, b), resolved.participants());
        assertTrue(resolved.successfulParticipant(a)); assertTrue(resolved.successfulParticipant(b)); assertFalse(resolved.successfulParticipant(spectator));
        assertTrue(loaded.hasSuccessfulParticipation(a)); assertTrue(loaded.hasSuccessfulParticipation(b)); assertFalse(loaded.hasSuccessfulParticipation(spectator));
    }
    @Test void guardOnlyVictoryAndDebugCancellationNeverRecordPlayerParticipation() {
        var data = new DefenseSavedData(); var guards = event(); victory(data, guards, Set.of());
        assertTrue(data.get(guards.id()).orElseThrow().participants().isEmpty()); assertTrue(data.active(guards.settlementId()).isEmpty());
        var player = UUID.randomUUID(); assertFalse(data.hasSuccessfulParticipation(player));
        var debug = event(UUID.randomUUID(), UUID.randomUUID(), Faction.UNDEAD, 100, false); victory(data, debug, Set.of(player));
        assertTrue(data.get(debug.id()).orElseThrow().participants().isEmpty()); assertFalse(data.hasSuccessfulParticipation(player));
        var canceled = event(); data.detect(canceled); data.activate(canceled.id(), 100);
        assertTrue(data.fail(canceled.id(), 120, ThreatOutcome.DEBUG_CANCELED)); assertFalse(data.resolve(canceled.id(), 130, Set.of(player)));
        assertFalse(reload(data).hasSuccessfulParticipation(player));
    }
    @Test void timeoutAndMissingReferencesReleaseSharedThreatAndRetainDedupe() {
        for (var reason : List.of(ThreatOutcome.TIMEOUT, ThreatOutcome.PARTY_MISSING, ThreatOutcome.SETTLEMENT_MISSING)) {
            var data = new DefenseSavedData(); var e = event(); data.detect(e); data.activate(e.id(), 100);
            long now = reason == ThreatOutcome.TIMEOUT ? 1300 : 200; assertTrue(data.fail(e.id(), now, reason));
            assertFalse(data.fail(e.id(), now, reason)); assertTrue(data.active(e.settlementId()).isEmpty());
            assertFalse(data.detect(event(UUID.randomUUID(), e.partyId(), Faction.PILLAGER, 1400, true)));
            assertTrue(data.detect(event(e.settlementId(), UUID.randomUUID(), Faction.BANDIT, 1400, true)));
            assertEquals(reason, reload(data).get(e.id()).orElseThrow().outcome());
        }
    }
    @Test void expirationCannotBecomeARewardAndTimestampsCannotMoveBackwards() {
        var data = new DefenseSavedData(); var e = event(); data.detect(e);
        assertFalse(data.activate(e.id(), 99)); assertFalse(data.activate(e.id(), 1300)); assertTrue(data.activate(e.id(), 110));
        assertFalse(data.activate(e.id(), 120)); assertFalse(data.fail(e.id(), 109, ThreatOutcome.PARTY_MISSING));
        assertFalse(data.fail(e.id(), 1299, ThreatOutcome.TIMEOUT)); assertFalse(data.fail(e.id(), 120, ThreatOutcome.VICTORY));
        data.updateProgress(e.id(), 0); assertFalse(data.resolve(e.id(), 109, Set.of())); assertFalse(data.resolve(e.id(), 1300, Set.of(UUID.randomUUID())));
        assertTrue(data.fail(e.id(), 1300, ThreatOutcome.TIMEOUT));
    }
    @Test void activeMaintenanceVisitsEveryEventAndReleasedSlotsStayOutOfTheSample() {
        var data = new DefenseSavedData(); var a = event(); var b = event(); var c = event();
        data.detect(a); data.detect(b); data.detect(c);
        assertEquals(a.id(), data.activeEvents(1).getFirst().id()); assertEquals(b.id(), data.activeEvents(1).getFirst().id());
        assertEquals(c.id(), data.activeEvents(1).getFirst().id()); assertEquals(a.id(), data.activeEvents(1).getFirst().id());
        assertTrue(data.fail(b.id(), 200, ThreatOutcome.PARTY_MISSING));
        assertEquals(Set.of(a.id(), c.id()), data.activeEvents(16).stream().map(SettlementThreatEvent::id).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class, () -> data.activeEvents(0)); assertThrows(IllegalArgumentException.class, () -> data.activeEvents(257));
        assertEquals(2, reload(data).activeEvents(16).size());
    }
    @Test void compareAndSetCannotChangeOwnershipDifficultyOrRewardEligibility() {
        var data = new DefenseSavedData(); var e = event(); data.detect(e);
        var foreign = new SettlementThreatEvent(e.id(), UUID.randomUUID(), e.dimension(), e.faction(), e.partyId(),
                e.threatRating(), e.recommendedLevel(), e.totalMembers(), e.remainingMembers(), e.state(), e.outcome(),
                e.detectedAt(), e.startedAt(), e.resolvedAt(), e.expiresAt(), e.debug(), e.rewardEligible(), e.participants());
        assertThrows(IllegalArgumentException.class, () -> data.replace(e, foreign));
        assertFalse(data.replace(e, e.activated(100).withProgress(0).resolved(200, Set.of())));
        assertTrue(data.activate(e.id(), 100)); var active = data.get(e.id()).orElseThrow();
        assertFalse(data.replace(active, e)); assertFalse(data.replace(e, e.activated(100)));
    }
    @Test void terminalSamplingAndPruningAreBoundedAndKeepAdvancementReceipts() {
        var data = new DefenseSavedData(); var settlement = UUID.randomUUID(); var player = UUID.randomUUID();
        var first = event(settlement, UUID.randomUUID(), Faction.PILLAGER, 100, true); victory(data, first, Set.of(player));
        var second = event(settlement, UUID.randomUUID(), Faction.UNDEAD, 300, true); victory(data, second, Set.of());
        var third = event(settlement, UUID.randomUUID(), Faction.BANDIT, 500, true); victory(data, third, Set.of());
        assertEquals(first.id(), data.terminalEvents(1).getFirst().id()); assertEquals(second.id(), data.terminalEvents(1).getFirst().id());
        assertEquals(third.id(), data.terminalEvents(1).getFirst().id()); assertEquals(first.id(), data.terminalEvents(1).getFirst().id());
        assertTrue(data.removeTerminal(third.id())); assertFalse(data.removeTerminal(third.id()));
        assertEquals(second.id(), data.latest(settlement).orElseThrow().id()); assertTrue(data.removeTerminal(first.id()));
        var loaded = reload(data); assertTrue(loaded.forParty(first.partyId()).isEmpty()); assertTrue(loaded.hasSuccessfulParticipation(player));
        assertEquals(1, loaded.terminalEvents(16).size()); var active = event(); loaded.detect(active); assertFalse(loaded.removeTerminal(active.id()));
        assertThrows(IllegalArgumentException.class, () -> loaded.terminalEvents(0)); assertThrows(IllegalArgumentException.class, () -> loaded.terminalEvents(257));
    }
    @Test void oldSaveWithoutDefenseStoreCreatesOnlyAdditiveNewMetadata() throws Exception {
        SharedConstants.tryDetectVersion(); var existing = directory.resolve("livingkingdoms_settlements.dat");
        byte[] prior = { 1, 2, 3, 4 }; Files.write(existing, prior);
        var storage = new DimensionDataStorage(directory.toFile(), null, null); var data = DefenseSavedData.getOrCreate(storage, directory);
        assertTrue(data.activeEvents().isEmpty()); assertTrue(data.terminalEvents().isEmpty());
        var e = event(); assertTrue(data.detect(e)); storage.save(); IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(prior, Files.readAllBytes(existing)); assertTrue(Files.exists(directory.resolve(DefenseSavedData.DATA_NAME + ".dat")));
    }
    @Test void physicalSavedDataReopensAnActiveThreatAndFrozenSuccessfulPlayers() {
        SharedConstants.tryDetectVersion(); var storage = new DimensionDataStorage(directory.toFile(), null, null);
        var data = DefenseSavedData.getOrCreate(storage, directory); var completed = event(); var player = UUID.randomUUID(); victory(data, completed, Set.of(player));
        var ongoing = event(); data.detect(ongoing); data.activate(ongoing.id(), 120); data.updateProgress(ongoing.id(), 2);
        storage.save(); IOUtilities.waitUntilIOWorkerComplete();
        var loaded = DefenseSavedData.getOrCreate(new DimensionDataStorage(directory.toFile(), null, null), directory);
        assertEquals(data.activeEvents(), loaded.activeEvents()); assertEquals(data.terminalEvents(), loaded.terminalEvents());
        assertTrue(loaded.hasSuccessfulParticipation(player)); assertFalse(loaded.isDirty());
    }
    @Test void futureAndTruncatedPhysicalFilesFailClosedWithoutBeingOverwritten() throws Exception {
        SharedConstants.tryDetectVersion(); var tag = new DefenseSavedData().save(new CompoundTag(), null); tag.putInt("schema_version", 99);
        var root = new CompoundTag(); root.put("data", tag); var file = directory.resolve(DefenseSavedData.DATA_NAME + ".dat"); NbtIo.writeCompressed(root, file);
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] before = Files.readAllBytes(file); var storage = new DimensionDataStorage(directory.toFile(), null, null);
            assertThrows(IllegalStateException.class, () -> DefenseSavedData.getOrCreate(storage, directory));
            storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(before, Files.readAllBytes(file));
            Files.write(file, new byte[] { 0x1f, (byte) 0x8b, 0x08 });
        }
    }
    @Test void missingWrongTypeUnknownFactionAndInvalidEventFieldsFailClosed() {
        var data = new DefenseSavedData(); data.detect(event()); var original = data.save(new CompoundTag(), null);
        for (String field : List.of("schema_version", "events", "successful_players")) {
            var corrupt = original.copy(); corrupt.remove(field); assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(corrupt, null));
        }
        for (String field : List.of("dimension", "remaining", "detected_at", "participants", "party")) {
            var corrupt = original.copy(); corrupt.getList("events", 10).getCompound(0).remove(field);
            assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(corrupt, null));
        }
        var booleanCorrupt = original.copy(); booleanCorrupt.getList("events", 10).getCompound(0).putByte("debug", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(booleanCorrupt, null));
        var faction = original.copy(); faction.getList("events", 10).getCompound(0).putString("faction", "unknown");
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(faction, null));
        var state = original.copy(); state.getList("events", 10).getCompound(0).putString("state", "ACTIVE");
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(state, null));
        var roster = original.copy(); roster.getList("events", 10).getCompound(0).putInt("remaining", 6);
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(roster, null));
        var timestamp = original.copy(); timestamp.getList("events", 10).getCompound(0).putLong("expires_at", 100);
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(timestamp, null));
        var wrongList = original.copy(); var strings = new ListTag(); strings.add(StringTag.valueOf("not an event")); wrongList.put("events", strings);
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(wrongList, null));
    }
    @Test void duplicatedEventPartyActiveSettlementAndParticipantsAreRejectedOnLoad() {
        var data = new DefenseSavedData(); var e = event(); data.detect(e); var original = data.save(new CompoundTag(), null);
        var duplicate = original.copy(); duplicate.getList("events", 10).add(duplicate.getList("events", 10).getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(duplicate, null));
        var partyDuplicate = original.copy(); var changed = partyDuplicate.getList("events", 10).getCompound(0).copy(); changed.putUUID("id", UUID.randomUUID());
        changed.putUUID("settlement", UUID.randomUUID()); partyDuplicate.getList("events", 10).add(changed);
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(partyDuplicate, null));
        var overbooked = original.copy(); var second = overbooked.getList("events", 10).getCompound(0).copy(); second.putUUID("id", UUID.randomUUID());
        second.putUUID("party", UUID.randomUUID()); overbooked.getList("events", 10).add(second);
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(overbooked, null));
        data.activate(e.id(), 100); data.updateProgress(e.id(), 0); var player = UUID.randomUUID(); data.resolve(e.id(), 200, Set.of(player));
        var players = data.save(new CompoundTag(), null); var list = players.getList("events", 10).getCompound(0).getList("participants", 10);
        list.add(list.getCompound(0).copy()); assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(players, null));
        var receipt = data.save(new CompoundTag(), null); receipt.put("successful_players", new ListTag());
        assertThrows(IllegalArgumentException.class, () -> DefenseSavedData.load(receipt, null));
    }
    @Test void boundStoresRejectOffThreadReadMutationAndPersistence() {
        var data = new DefenseSavedData(Thread.currentThread()); var e = event(); data.detect(e);
        CompletableFuture.runAsync(() -> {
            assertThrows(IllegalStateException.class, () -> data.get(e.id()));
            assertThrows(IllegalStateException.class, () -> data.detect(event()));
            assertThrows(IllegalStateException.class, () -> data.updateProgress(e.id(), 0));
            assertThrows(IllegalStateException.class, () -> data.resolve(e.id(), 200, Set.of()));
            assertThrows(IllegalStateException.class, () -> data.terminalEvents(16));
            assertThrows(IllegalStateException.class, () -> data.save(new CompoundTag(), null));
        }).join(); assertEquals(1, data.activeEvents().size());
    }
}

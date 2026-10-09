package dev.livingkingdoms.defense.domain;

import dev.livingkingdoms.faction.Faction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementThreatEventTest {
    private static SettlementThreatEvent detected() {
        return new SettlementThreatEvent(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", Faction.PILLAGER,
                UUID.randomUUID(), 12, 3, 5, 5, ThreatState.DETECTED, ThreatOutcome.NONE, 100, -1, -1, 1300, false, true, Set.of());
    }
    @Test void immutableLifecycleCountsOnlyItsRosterAndKeepsSettlementIdentity() {
        var event = detected(); assertTrue(event.threatened()); assertEquals(0, event.defeatedMembers());
        var active = event.activated(120).withProgress(2); assertEquals(3, active.defeatedMembers());
        var player = UUID.randomUUID(); var contributors = new HashSet<>(Set.of(player));
        var resolved = active.withProgress(0).resolved(300, contributors); contributors.clear();
        assertEquals(event.id(), resolved.id()); assertEquals(event.settlementId(), resolved.settlementId());
        assertEquals(event.partyId(), resolved.partyId()); assertEquals(ThreatState.RESOLVED, resolved.state());
        assertFalse(resolved.threatened()); assertTrue(resolved.successfulParticipant(player));
        assertFalse(resolved.successfulParticipant(UUID.randomUUID())); assertEquals(Set.of(player), resolved.participants());
        assertThrows(UnsupportedOperationException.class, () -> resolved.participants().clear());
    }
    @Test void guardOnlyVictoryIsSuccessfulWithoutPlayerEligibility() {
        var resolved = detected().activated(100).withProgress(0).resolved(120, Set.of());
        assertEquals(ThreatOutcome.VICTORY, resolved.outcome()); assertTrue(resolved.participants().isEmpty());
        assertFalse(resolved.successfulParticipant(UUID.randomUUID()));
    }
    @Test void unrelatedDeathAndResurrectionCannotBeExpressedByThreatProgress() {
        var active = detected().activated(100).withProgress(3);
        assertThrows(IllegalArgumentException.class, () -> active.withProgress(4));
        assertThrows(IllegalArgumentException.class, () -> active.withProgress(-1));
        assertThrows(IllegalArgumentException.class, () -> active.resolved(200, Set.of()));
        var failed = active.failed(1300, ThreatOutcome.TIMEOUT);
        assertThrows(IllegalArgumentException.class, () -> failed.withProgress(0));
        assertThrows(IllegalArgumentException.class, () -> failed.activated(200));
        assertThrows(IllegalArgumentException.class, () -> failed.failed(1400, ThreatOutcome.PARTY_MISSING));
    }
    @Test void expirationAndMissingPartyFailuresNeverRecordSuccess() {
        var event = detected();
        assertThrows(IllegalArgumentException.class, () -> event.failed(1299, ThreatOutcome.TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> event.activated(1300));
        var failed = event.failed(1300, ThreatOutcome.TIMEOUT); assertFalse(failed.threatened());
        assertEquals(ThreatState.FAILED, failed.state()); assertEquals(-1, failed.startedAt());
        assertTrue(failed.participants().isEmpty());
        assertEquals(ThreatOutcome.PARTY_MISSING, detected().activated(100).failed(200, ThreatOutcome.PARTY_MISSING).outcome());
        assertEquals(ThreatOutcome.DEBUG_CANCELED, detected().activated(100).failed(200, ThreatOutcome.DEBUG_CANCELED).outcome());
    }
    @Test void difficultyDimensionRosterFactionAndTimestampsAreValidated() {
        var e = detected();
        assertThrows(IllegalArgumentException.class, () -> changed(e, "overworld", Faction.PILLAGER, 12, 3, 5, 5, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, "minecraft:bad dimension", Faction.PILLAGER, 12, 3, 5, 5, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), Faction.ALLIED_KINGDOM, 12, 3, 5, 5, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), e.faction(), 0, 3, 5, 5, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), e.faction(), 12, 101, 5, 5, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), e.faction(), 12, 3, 17, 5, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), e.faction(), 12, 3, 5, 6, 100, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), e.faction(), 12, 3, 5, 5, -1, 1300));
        assertThrows(IllegalArgumentException.class, () -> changed(e, e.dimension(), e.faction(), 12, 3, 5, 5, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> e.activated(99));
        assertThrows(IllegalArgumentException.class, () -> e.activated(100).withProgress(0).resolved(1300, Set.of()));
    }
    private static SettlementThreatEvent changed(SettlementThreatEvent e, String dimension, Faction faction,
                                                  int rating, int level, int total, int remaining, long now, long expires) {
        return new SettlementThreatEvent(e.id(), e.settlementId(), dimension, faction, e.partyId(), rating, level,
                total, remaining, ThreatState.DETECTED, ThreatOutcome.NONE, now, -1, -1, expires, false, true, Set.of());
    }
}

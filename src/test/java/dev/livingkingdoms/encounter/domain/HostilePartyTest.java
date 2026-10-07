package dev.livingkingdoms.encounter.domain;

import dev.livingkingdoms.faction.Faction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostilePartyTest {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final OriginRegion origin = new OriginRegion("minecraft:overworld", 42, 70, -31, 32);

    private HostileParty party(Set<UUID> members, Set<UUID> remaining, PartyState state) {
        return new HostileParty(UUID.randomUUID(), Faction.PILLAGER, PartyType.PILLAGER_PATROL,
                origin, UUID.randomUUID(), state, members, remaining, 10, 4, true, false);
    }

    @Test
    void defeatRequiresEveryMemberAndRepeatedOrUnrelatedDeathsAreIdempotent() {
        HostileParty original = party(Set.of(first, second), Set.of(first, second), PartyState.ALIVE);
        assertSame(original, original.withMemberDeath(UUID.randomUUID()));
        HostileParty wounded = original.withMemberDeath(first);
        assertEquals(PartyState.ALIVE, wounded.state());
        assertEquals(Set.of(second), wounded.remainingMembers());
        assertEquals(Set.of(first, second), wounded.memberIds());
        assertSame(wounded, wounded.withMemberDeath(first));
        HostileParty defeated = wounded.withMemberDeath(second);
        assertEquals(PartyState.DEFEATED, defeated.state());
        assertTrue(defeated.remainingMembers().isEmpty());
        assertSame(defeated, defeated.withMemberDeath(second));
        assertEquals(Set.of(first, second), original.remainingMembers());
        assertEquals(original.id(), defeated.id());
        assertEquals(original.reputationReward(), defeated.reputationReward());
        assertEquals(original.origin(), defeated.origin());
        assertEquals(original.associatedSettlementId(), defeated.associatedSettlementId());
    }

    @Test
    void rostersAreImmutableSnapshotsOfCallerOwnedSets() {
        Set<UUID> members = new HashSet<>(Set.of(first, second));
        Set<UUID> remaining = new HashSet<>(members);
        HostileParty party = party(members, remaining, PartyState.ALIVE);
        members.clear();
        remaining.clear();
        assertEquals(Set.of(first, second), party.memberIds());
        assertEquals(Set.of(first, second), party.remainingMembers());
        assertThrows(UnsupportedOperationException.class, () -> party.memberIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> party.remainingMembers().clear());
    }

    @Test
    void vanillaConversionReplacesLivingIdentityWithoutRewardOrDefeat() {
        HostileParty party = party(Set.of(first, second), Set.of(first, second), PartyState.ALIVE);
        UUID drowned = UUID.randomUUID();
        HostileParty converted = party.replaceMember(first, drowned);
        assertEquals(Set.of(second, drowned), converted.memberIds());
        assertEquals(converted.memberIds(), converted.remainingMembers());
        assertEquals(PartyState.ALIVE, converted.state());
        assertEquals(party.id(), converted.id());
        assertSame(converted, converted.replaceMember(drowned, drowned));
        assertThrows(IllegalArgumentException.class, () -> party.replaceMember(first, second));
        assertThrows(IllegalArgumentException.class, () -> party.replaceMember(UUID.randomUUID(), drowned));
        assertThrows(IllegalArgumentException.class, () -> party.withMemberDeath(first).replaceMember(first, drowned));
    }

    @Test
    void stateCannotClaimDefeatWhileAnyRosterMemberRemains() {
        assertThrows(IllegalArgumentException.class, () -> party(Set.of(first), Set.of(first), PartyState.DEFEATED));
        assertThrows(IllegalArgumentException.class, () -> party(Set.of(first), Set.of(), PartyState.ALIVE));
        assertThrows(IllegalArgumentException.class, () -> party(Set.of(first), Set.of(second), PartyState.ALIVE));
        assertThrows(IllegalArgumentException.class, () -> party(Set.of(), Set.of(), PartyState.DEFEATED));
        Set<UUID> large = new HashSet<>();
        for (int i = 0; i < 17; i++) large.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> party(large, large, PartyState.ALIVE));
    }

    @Test
    void distinctTypesRequireTheirOwnFactionsWithoutSettlementDependency() {
        HostileParty undead = new HostileParty(UUID.randomUUID(), Faction.UNDEAD, PartyType.UNDEAD_HORDE,
                origin, null, PartyState.ALIVE, Set.of(first), Set.of(first), 20, 2, false, true);
        assertNull(undead.associatedSettlementId());
        assertEquals(Faction.UNDEAD, undead.faction());
        assertThrows(IllegalArgumentException.class, () -> new HostileParty(UUID.randomUUID(), Faction.BANDIT,
                PartyType.UNDEAD_HORDE, origin, null, PartyState.ALIVE,
                Set.of(first), Set.of(first), 20, 2, false, true));
        for (PartyType type : PartyType.values()) assertEquals(type, PartyType.fromId(type.id()));
        assertThrows(IllegalArgumentException.class, () -> PartyType.fromId("unsupported"));
    }

    @Test
    void threatAndRewardAreBoundedMetadataIndependentOfEntityType() {
        assertThrows(IllegalArgumentException.class, () -> withRatings(0, 2));
        assertThrows(IllegalArgumentException.class, () -> withRatings(101, 2));
        assertThrows(IllegalArgumentException.class, () -> withRatings(10, -1));
        assertThrows(IllegalArgumentException.class, () -> withRatings(10, 1_000_001));
        assertEquals(0, withRatings(1, 0).reputationReward());
        assertEquals(100, withRatings(100, 1_000_000).threatRating());
    }

    private HostileParty withRatings(int threat, int reward) {
        return new HostileParty(UUID.randomUUID(), Faction.PILLAGER, PartyType.PILLAGER_PATROL,
                origin, null, PartyState.ALIVE, Set.of(first), Set.of(first), threat, reward, false, true);
    }

    @Test
    void approximateRegionsRequireValidDimensionsAndBoundedRadii() {
        assertThrows(IllegalArgumentException.class, () -> new OriginRegion("overworld", 0, 0, 0, 32));
        assertThrows(IllegalArgumentException.class, () -> new OriginRegion("minecraft:overworld", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OriginRegion("minecraft:overworld", 0, 0, 0, 4097));
        assertEquals(4096, new OriginRegion("livingkingdoms:future/dimension", 0, 0, 0, 4096).radius());
    }
}

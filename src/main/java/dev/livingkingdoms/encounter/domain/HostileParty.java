package dev.livingkingdoms.encounter.domain;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelSummary;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable metadata. Minecraft retains entity health, equipment, positions and AI. */
public record HostileParty(UUID id, Faction faction, PartyType type, OriginRegion origin,
                           UUID associatedSettlementId, PartyState state, Set<UUID> memberIds,
                           Set<UUID> remainingMembers, int threatRating, int reputationReward,
                           boolean debug, boolean rewardEligible, LevelSummary levels) {
    /** Source/save compatibility: pre-progression parties are vanilla-level snapshots. */
    public HostileParty(UUID id, Faction faction, PartyType type, OriginRegion origin, UUID associatedSettlementId,
                        PartyState state, Set<UUID> memberIds, Set<UUID> remainingMembers, int threatRating,
                        int reputationReward, boolean debug, boolean rewardEligible) {
        this(id, faction, type, origin, associatedSettlementId, state, memberIds, remainingMembers,
                threatRating, reputationReward, debug, rewardEligible, LevelSummary.uniform(memberIds.size(), 1));
    }
    public HostileParty {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(state, "state");
        memberIds = Set.copyOf(Objects.requireNonNull(memberIds, "memberIds"));
        remainingMembers = Set.copyOf(Objects.requireNonNull(remainingMembers, "remainingMembers"));
        Objects.requireNonNull(levels, "levels");
        if (levels.count() != memberIds.size()) throw new IllegalArgumentException("Party levels must describe the full roster");
        if (faction != type.faction()) {
            throw new IllegalArgumentException("Party faction does not match its type");
        }
        if (memberIds.isEmpty() || memberIds.size() > 16) {
            throw new IllegalArgumentException("Party must contain between 1 and 16 members");
        }
        if (!memberIds.containsAll(remainingMembers)) {
            throw new IllegalArgumentException("Remaining members must belong to the party");
        }
        if ((state == PartyState.ALIVE) != !remainingMembers.isEmpty()) {
            throw new IllegalArgumentException("Party state does not match remaining members");
        }
        if (threatRating < 1 || threatRating > 100) {
            throw new IllegalArgumentException("Threat rating must be between 1 and 100");
        }
        if (reputationReward < 0 || reputationReward > 1_000_000) {
            throw new IllegalArgumentException("Reputation reward must be between 0 and 1000000");
        }
    }

    /** Duplicate and unrelated deaths are harmless; the retained roster identifies repeats. */
    public HostileParty withMemberDeath(UUID member) {
        Objects.requireNonNull(member, "member");
        if (!remainingMembers.contains(member)) return this;
        Set<UUID> remaining = new HashSet<>(remainingMembers);
        remaining.remove(member);
        return new HostileParty(id, faction, type, origin, associatedSettlementId,
                remaining.isEmpty() ? PartyState.DEFEATED : PartyState.ALIVE,
                memberIds, remaining, threatRating, reputationReward, debug, rewardEligible, levels);
    }

    /** Vanilla transformations replace identity without counting as a defeat. */
    public HostileParty replaceMember(UUID oldMember, UUID replacement) {
        Objects.requireNonNull(oldMember, "oldMember");
        Objects.requireNonNull(replacement, "replacement");
        if (!remainingMembers.contains(oldMember)) {
            throw new IllegalArgumentException("Only a living member can be replaced");
        }
        if (oldMember.equals(replacement)) return this;
        if (memberIds.contains(replacement)) {
            throw new IllegalArgumentException("Replacement is already a party member");
        }
        Set<UUID> roster = new HashSet<>(memberIds);
        Set<UUID> remaining = new HashSet<>(remainingMembers);
        roster.remove(oldMember);
        remaining.remove(oldMember);
        roster.add(replacement);
        remaining.add(replacement);
        return new HostileParty(id, faction, type, origin, associatedSettlementId, state,
                roster, remaining, threatRating, reputationReward, debug, rewardEligible, levels);
    }
}

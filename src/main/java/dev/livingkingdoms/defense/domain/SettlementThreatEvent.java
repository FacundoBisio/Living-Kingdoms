package dev.livingkingdoms.defense.domain;

import dev.livingkingdoms.faction.Faction;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared encounter linkage. Entity positions, combat AI and participation damage stay in their existing stores. */
public record SettlementThreatEvent(UUID id, UUID settlementId, String dimension, Faction faction,
                                    UUID partyId, int threatRating, int recommendedLevel,
                                    int totalMembers, int remainingMembers, ThreatState state,
                                    ThreatOutcome outcome, long detectedAt, long startedAt,
                                    long resolvedAt, long expiresAt, boolean debug,
                                    boolean rewardEligible, Set<UUID> participants) {
    public static final int MAX_MEMBERS = 16;
    public static final int MAX_PARTICIPANTS = 64;

    public SettlementThreatEvent {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(dimension, "dimension"); Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(partyId, "partyId"); Objects.requireNonNull(state, "state");
        Objects.requireNonNull(outcome, "outcome");
        participants = Set.copyOf(Objects.requireNonNull(participants, "participants"));
        if (ResourceLocation.tryParse(dimension) == null || !dimension.contains(":"))
            throw new IllegalArgumentException("Invalid threat dimension");
        if (faction.isAllied()) throw new IllegalArgumentException("An allied party is not a local threat");
        if (threatRating < 1 || threatRating > 100 || recommendedLevel < 1 || recommendedLevel > 100)
            throw new IllegalArgumentException("Invalid threat difficulty");
        if (totalMembers < 1 || totalMembers > MAX_MEMBERS || remainingMembers < 0 || remainingMembers > totalMembers)
            throw new IllegalArgumentException("Invalid threat roster counts");
        if (detectedAt < 0 || expiresAt <= detectedAt || startedAt < -1 || resolvedAt < -1)
            throw new IllegalArgumentException("Invalid threat timestamps");
        if (startedAt != -1 && (startedAt < detectedAt || startedAt >= expiresAt))
            throw new IllegalArgumentException("Threat must start before it expires");
        if (participants.size() > MAX_PARTICIPANTS)
            throw new IllegalArgumentException("Too many defense participants");
        switch (state) {
            case DETECTED -> {
                if (startedAt != -1 || resolvedAt != -1 || outcome != ThreatOutcome.NONE || !participants.isEmpty())
                    throw new IllegalArgumentException("Invalid detected threat state");
            }
            case ACTIVE -> {
                if (startedAt == -1 || resolvedAt != -1 || outcome != ThreatOutcome.NONE || !participants.isEmpty())
                    throw new IllegalArgumentException("Invalid active threat state");
            }
            case RESOLVED -> {
                if (startedAt == -1 || resolvedAt < startedAt || resolvedAt >= expiresAt
                        || outcome != ThreatOutcome.VICTORY || remainingMembers != 0 || !rewardEligible && !participants.isEmpty())
                    throw new IllegalArgumentException("Invalid successful threat state");
            }
            case FAILED -> {
                if (resolvedAt < Math.max(detectedAt, startedAt) || outcome == ThreatOutcome.NONE
                        || outcome == ThreatOutcome.VICTORY || !participants.isEmpty()
                        || outcome == ThreatOutcome.TIMEOUT && resolvedAt < expiresAt)
                    throw new IllegalArgumentException("Invalid failed threat state");
            }
        }
    }

    public boolean threatened() { return state.threatened(); }
    public int defeatedMembers() { return totalMembers - remainingMembers; }
    public boolean successfulParticipant(UUID player) {
        return state == ThreatState.RESOLVED && rewardEligible && participants.contains(player);
    }
    public SettlementThreatEvent withProgress(int remaining) {
        if (!threatened() || remaining < 0 || remaining > remainingMembers)
            throw new IllegalArgumentException("Threat progress cannot resurrect members or reopen a terminal event");
        return copy(remaining, state, outcome, startedAt, resolvedAt, participants);
    }
    public SettlementThreatEvent activated(long now) {
        if (state != ThreatState.DETECTED) throw new IllegalArgumentException("Only a detected threat can activate");
        return copy(remainingMembers, ThreatState.ACTIVE, ThreatOutcome.NONE, now, -1, Set.of());
    }
    public SettlementThreatEvent resolved(long now, Set<UUID> eligiblePlayers) {
        if (state != ThreatState.ACTIVE || remainingMembers != 0)
            throw new IllegalArgumentException("A successful threat needs an active defeated party");
        return copy(0, ThreatState.RESOLVED, ThreatOutcome.VICTORY, startedAt, now, eligiblePlayers);
    }
    public SettlementThreatEvent failed(long now, ThreatOutcome reason) {
        if (!threatened()) throw new IllegalArgumentException("A terminal threat cannot fail again");
        return copy(remainingMembers, ThreatState.FAILED, reason, startedAt, now, Set.of());
    }
    private SettlementThreatEvent copy(int remaining, ThreatState nextState, ThreatOutcome nextOutcome,
                                       long started, long resolved, Set<UUID> players) {
        return new SettlementThreatEvent(id, settlementId, dimension, faction, partyId, threatRating,
                recommendedLevel, totalMembers, remaining, nextState, nextOutcome, detectedAt,
                started, resolved, expiresAt, debug, rewardEligible, players);
    }
}

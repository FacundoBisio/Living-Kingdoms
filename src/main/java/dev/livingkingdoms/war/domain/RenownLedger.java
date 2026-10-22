package dev.livingkingdoms.war.domain;

import dev.livingkingdoms.encounter.domain.PartyType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One player's renown. {@code rewarded} is the highest rank whose reward was already handed out, so a
 * rank reward is a one-time receipt that survives restarts and is never replayed.
 */
public record RenownLedger(int points, RenownRank rewarded) {
    public static final int MAX_POINTS = 1_000_000;
    public static final int MAX_POINTS_PER_PARTY = 500;

    public RenownLedger {
        Objects.requireNonNull(rewarded, "rewarded");
        if (points < 0 || points > MAX_POINTS) throw new IllegalArgumentException("Renown outside supported bounds");
        if (rewarded.ordinal() > RenownRank.of(points).ordinal()) throw new IllegalArgumentException("Rewarded rank exceeds earned rank");
    }

    public static RenownLedger empty() { return new RenownLedger(0, RenownRank.RECRUIT); }

    public RenownRank rank() { return RenownRank.of(points); }

    public RenownLedger add(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Renown awards cannot be negative");
        return new RenownLedger((int) Math.min(MAX_POINTS, (long) points + amount), rewarded);
    }

    /** Ranks earned but not yet rewarded, lowest first. */
    public List<RenownRank> pending() {
        List<RenownRank> result = new ArrayList<>();
        for (RenownRank rank : RenownRank.values())
            if (rank.ordinal() > rewarded.ordinal() && rank.ordinal() <= rank().ordinal()) result.add(rank);
        return List.copyOf(result);
    }

    public RenownLedger rewardedThrough(RenownRank rank) {
        Objects.requireNonNull(rank, "rank");
        return rank.ordinal() <= rewarded.ordinal() ? this : new RenownLedger(points, rank);
    }

    /** Renown for helping to defeat one party: its size times its level, doubled for kingdom warbands. */
    public static int pointsForDefeat(PartyType type, int members, double averageLevel) {
        Objects.requireNonNull(type, "type");
        if (members < 1 || members > 16 || !Double.isFinite(averageLevel) || averageLevel < 1 || averageLevel > 100)
            throw new IllegalArgumentException("Invalid renown inputs");
        long points = (long) members * (long) Math.ceil(averageLevel) * (type == PartyType.WARBAND ? 2 : 1);
        return (int) Math.min(MAX_POINTS_PER_PARTY, points);
    }
}

package dev.livingkingdoms.war.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * War state of one NPC kingdom. Strength grows when its raids are not stopped and shrinks when players
 * break its warbands or its garrison, so a long-ignored enemy becomes a real problem and a defeated one
 * calms down. Immutable; the store replaces it.
 */
public record WarFront(KingdomStance stance, int strength, long nextRaidAt, int raidsLaunched, int raidsRepelled,
                       UUID garrisonParty, long nextGarrisonAt, boolean standardsPlaced) {
    public static final int MIN_STRENGTH = 1;
    public static final int MAX_STRENGTH = 10;

    public WarFront {
        Objects.requireNonNull(stance, "stance");
        if (strength < MIN_STRENGTH || strength > MAX_STRENGTH) throw new IllegalArgumentException("War strength must be 1..10");
        if (nextRaidAt < 0 || nextGarrisonAt < 0 || raidsLaunched < 0 || raidsRepelled < 0 || raidsRepelled > raidsLaunched)
            throw new IllegalArgumentException("Invalid war front counters");
        if (stance != KingdomStance.HOSTILE && garrisonParty != null)
            throw new IllegalArgumentException("Only hostile kingdoms keep a garrison");
    }

    public static WarFront initial(KingdomStance stance) {
        return new WarFront(stance, MIN_STRENGTH, 0, 0, 0, null, 0, false);
    }

    public boolean hostile() { return stance == KingdomStance.HOSTILE; }

    public WarFront withStance(KingdomStance next) {
        Objects.requireNonNull(next, "next");
        if (next == stance) return this;
        return new WarFront(next, strength, nextRaidAt, raidsLaunched, raidsRepelled,
                next == KingdomStance.HOSTILE ? garrisonParty : null, nextGarrisonAt, standardsPlaced);
    }

    public boolean raidReady(long now) { return hostile() && now >= nextRaidAt; }

    public WarFront raidLaunched(long now, int cooldown) {
        if (!hostile()) throw new IllegalStateException("Independent kingdoms do not raid");
        return new WarFront(stance, strength, saturatedAdd(now, cooldown), raidsLaunched + 1, raidsRepelled,
                garrisonParty, nextGarrisonAt, standardsPlaced);
    }

    /** A raid ended in a player victory. */
    public WarFront raidRepelled() {
        return new WarFront(stance, Math.max(MIN_STRENGTH, strength - 1), nextRaidAt, raidsLaunched,
                Math.min(raidsLaunched, raidsRepelled + 1), garrisonParty, nextGarrisonAt, standardsPlaced);
    }

    /** A raid timed out, was ignored or withdrew: the kingdom grows bolder. */
    public WarFront raidUnanswered() {
        return new WarFront(stance, Math.min(MAX_STRENGTH, strength + 1), nextRaidAt, raidsLaunched, raidsRepelled,
                garrisonParty, nextGarrisonAt, standardsPlaced);
    }

    public boolean garrisonReady(long now) { return hostile() && garrisonParty == null && now >= nextGarrisonAt; }

    public WarFront garrisonSpawned(UUID party) {
        Objects.requireNonNull(party, "party");
        if (!hostile()) throw new IllegalStateException("Independent kingdoms do not keep a garrison");
        return new WarFront(stance, strength, nextRaidAt, raidsLaunched, raidsRepelled, party, nextGarrisonAt, standardsPlaced);
    }

    /** The garrison is gone; a defeated garrison also weakens the kingdom. */
    public WarFront garrisonCleared(boolean defeated, long now, int cooldown) {
        return new WarFront(stance, defeated ? Math.max(MIN_STRENGTH, strength - 1) : strength, nextRaidAt,
                raidsLaunched, raidsRepelled, null, saturatedAdd(now, cooldown), standardsPlaced);
    }

    /** Breaking the garrison of a kingdom already at its weakest forces it to sue for peace. */
    public boolean capitulatesOnGarrisonDefeat() { return hostile() && strength == MIN_STRENGTH; }

    /** Peace: the kingdom becomes independent; standards stay as a trophy, war counters are kept for history. */
    public WarFront capitulated(long now) {
        return new WarFront(KingdomStance.INDEPENDENT, MIN_STRENGTH, nextRaidAt, raidsLaunched, raidsRepelled, null,
                Math.max(nextGarrisonAt, now), standardsPlaced);
    }

    public WarFront withStrength(int next) {
        return new WarFront(stance, Math.clamp(next, MIN_STRENGTH, MAX_STRENGTH), nextRaidAt, raidsLaunched, raidsRepelled,
                garrisonParty, nextGarrisonAt, standardsPlaced);
    }

    public WarFront withStandardsPlaced() {
        if (standardsPlaced) return this;
        return new WarFront(stance, strength, nextRaidAt, raidsLaunched, raidsRepelled, garrisonParty, nextGarrisonAt, true);
    }

    private static long saturatedAdd(long now, int ticks) {
        if (now < 0 || ticks < 0) throw new IllegalArgumentException("Invalid war clock");
        return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }
}

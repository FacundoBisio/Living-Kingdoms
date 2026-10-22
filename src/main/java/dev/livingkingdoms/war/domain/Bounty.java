package dev.livingkingdoms.war.domain;

import dev.livingkingdoms.encounter.domain.PartyType;

import java.util.Objects;

/** Loot carried by a defeated party: emeralds, iron for warbands, and experience. */
public record Bounty(int emeralds, int iron, int experience) {
    public static final int MAX_EMERALDS = 64;
    public static final int MAX_IRON = 32;
    public static final int MAX_EXPERIENCE = 2000;

    public Bounty {
        if (emeralds < 0 || emeralds > MAX_EMERALDS || iron < 0 || iron > MAX_IRON || experience < 0 || experience > MAX_EXPERIENCE)
            throw new IllegalArgumentException("Bounty outside supported bounds");
    }

    public static final Bounty NONE = new Bounty(0, 0, 0);

    public boolean empty() { return emeralds == 0 && iron == 0 && experience == 0; }

    /** Tunable coefficients; validated once when the server config is read. */
    public record Rules(double emeraldBase, double emeraldPerLevel, int emeraldCap, double experiencePerLevel, int experienceCap) {
        public Rules {
            if (!Double.isFinite(emeraldBase) || emeraldBase < 0 || emeraldBase > 8
                    || !Double.isFinite(emeraldPerLevel) || emeraldPerLevel < 0 || emeraldPerLevel > 4
                    || emeraldCap < 0 || emeraldCap > MAX_EMERALDS
                    || !Double.isFinite(experiencePerLevel) || experiencePerLevel < 0 || experiencePerLevel > 50
                    || experienceCap < 0 || experienceCap > MAX_EXPERIENCE) throw new IllegalArgumentException("Invalid bounty rules");
        }
        public static Rules defaults() { return new Rules(0.5, 0.15, 24, 3.0, 400); }
    }

    /**
     * Higher level and larger parties carry more. Undead carry little coin but more experience; warbands are
     * paid soldiers with supplies, so they also carry iron proportional to their kingdom's strength.
     */
    public static Bounty forParty(PartyType type, int members, double averageLevel, int strength, Rules rules) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rules, "rules");
        if (members < 1 || members > 16 || !Double.isFinite(averageLevel) || averageLevel < 1 || averageLevel > 100
                || strength < WarFront.MIN_STRENGTH || strength > WarFront.MAX_STRENGTH)
            throw new IllegalArgumentException("Invalid bounty inputs");
        double coin = switch (type) {
            case UNDEAD_HORDE -> 0.5;
            case WARBAND -> 2.0;
            default -> 1.0;
        };
        double learning = switch (type) {
            case UNDEAD_HORDE, WARBAND -> 1.5;
            default -> 1.0;
        };
        int emeralds = (int) Math.min(rules.emeraldCap(), Math.round(members * (rules.emeraldBase() + averageLevel * rules.emeraldPerLevel()) * coin));
        int experience = (int) Math.min(rules.experienceCap(), Math.round(members * averageLevel * rules.experiencePerLevel() * learning));
        int iron = type == PartyType.WARBAND ? Math.min(16, strength + members / 2) : 0;
        return new Bounty(emeralds, iron, experience);
    }
}

package dev.livingkingdoms.rpg.domain;

import java.util.Locale;

/**
 * Kingdom-wide mood, derived on demand from state that already exists (food stock, beds, taverns, kingdom rank).
 * Never stored. The band scales everyone's work speed by a bounded amount; the issue names the one thing to fix.
 */
public record Happiness(int value, Band band, Issue issue) {
    public static final int BASE = 40, FED = 15, HUNGRY = -25, HOUSED = 15, HOMELESS = -15, PER_TAVERN_LEVEL = 5, MAX_TAVERN_LEVELS = 3, MAX_RANK_BONUS = 15;
    /** Kingdoms without a player-run economy (NPC kingdoms) have no mood. */
    public static final Happiness NEUTRAL = new Happiness(50, Band.CONTENT, Issue.NONE);

    public enum Band {
        UNHAPPY(0.9), CONTENT(1.0), HAPPY(1.1);
        private final double workMultiplier;
        Band(double workMultiplier) { this.workMultiplier = workMultiplier; }
        public double workMultiplier() { return workMultiplier; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public static Band of(int value) { return value < 40 ? UNHAPPY : value >= 75 ? HAPPY : CONTENT; }
    }

    /** The most useful thing to fix, worst first. NONE once the kingdom is happy. */
    public enum Issue {
        NONE, NO_FOOD, NO_BEDS, NO_TAVERN;
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** {@code beds} counts house beds; the Mayor lives in the Town Hall or camp and needs none. */
    public static Happiness of(int population, int beds, boolean fed, int tavernLevels, int rank) {
        if (population < 0 || beds < 0 || tavernLevels < 0 || rank < 0) throw new IllegalArgumentException("Negative happiness input");
        boolean housed = beds >= population - 1;
        int value = Math.clamp(BASE + (fed ? FED : HUNGRY) + (housed ? HOUSED : HOMELESS)
                + PER_TAVERN_LEVEL * Math.min(MAX_TAVERN_LEVELS, tavernLevels) + Math.min(MAX_RANK_BONUS, rank), 0, 100);
        var band = Band.of(value);
        var issue = !fed ? Issue.NO_FOOD : !housed ? Issue.NO_BEDS : band != Band.HAPPY && tavernLevels == 0 ? Issue.NO_TAVERN : Issue.NONE;
        return new Happiness(value, band, issue);
    }
}

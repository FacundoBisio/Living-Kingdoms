package dev.livingkingdoms.rpg.domain;

import java.util.Locale;
import java.util.UUID;

/** One fixed personality trait per resident, derived from the citizen id so it needs no storage and never changes. */
public enum Trait {
    HARD_WORKING(1.1), LAZY(0.9),
    /** Keeps working through alarms and threat alerts; still runs from an enemy next to them. */
    BRAVE(1.0);

    public static final double MIN_WORK = 0.8, MAX_WORK = 1.2;
    private final double workMultiplier;
    Trait(double workMultiplier) { this.workMultiplier = workMultiplier; }
    public double workMultiplier() { return workMultiplier; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public static Trait of(UUID citizen) { return values()[Math.floorMod(citizen.hashCode(), values().length)]; }

    /** Kingdom mood times the resident's trait, kept inside [{@link #MIN_WORK}, {@link #MAX_WORK}]. */
    public static double work(Happiness.Band band, Trait trait) { return Math.clamp(band.workMultiplier() * trait.workMultiplier(), MIN_WORK, MAX_WORK); }
}

package dev.livingkingdoms.rpg.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Optional trial handicaps the challenger picks before starting. Each active modifier adds a Royal Whetstone;
 * {@link #MAX_ACTIVE} together also add a tome. Travels as a bit mask and lives only in the transient run.
 */
public enum ChallengeModifier {
    /** Every wave brings twice as many enemies. */
    HORDE,
    /** The challenger cannot heal while the trial runs. */
    NO_HEALING,
    /** The arena shrinks to {@link #TIGHT_RADIUS} blocks. */
    TIGHT;

    public static final int MAX_ACTIVE = 2, TIGHT_RADIUS = 32;

    public int bit() { return 1 << ordinal(); }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    /** A mask from the network is valid when it names only known modifiers and at most {@link #MAX_ACTIVE}. */
    public static boolean valid(int mask) { return mask >= 0 && mask < 1 << values().length && Integer.bitCount(mask) <= MAX_ACTIVE; }
    public static Set<ChallengeModifier> of(int mask) {
        if (!valid(mask)) throw new IllegalArgumentException("Invalid challenge modifiers: " + mask);
        var set = EnumSet.noneOf(ChallengeModifier.class);
        for (var modifier : values()) if ((mask & modifier.bit()) != 0) set.add(modifier);
        return set;
    }
    public static int extraWhetstones(int mask) { return Integer.bitCount(mask); }
    public static int extraTomes(int mask) { return Integer.bitCount(mask) >= MAX_ACTIVE ? 1 : 0; }
}

package dev.livingkingdoms.citizen;

import dev.livingkingdoms.settlement.domain.SettlementLifecycle;

/** Pure policy boundary: housing and lifecycle stay mandatory when future modifiers change. */
public final class ImmigrationPolicy {
    private ImmigrationPolicy() {}
    public static boolean eligible(boolean enabled, SettlementLifecycle lifecycle, int freeHousing,
            int pending, int minimumFreeHousing, int maximumPending) {
        return enabled && lifecycle == SettlementLifecycle.ESTABLISHED
                && freeHousing >= Math.max(1, minimumFreeHousing) && pending < maximumPending;
    }
    /** Placeholder conditions, deliberately neutral until a real regional simulation supplies them. */
    public record Factors(double food, double security, double prosperity, int reputation,
                          int settlementLevel, boolean recentAttack) {
        public static Factors neutral(int reputation, int level) { return new Factors(1,1,1,reputation,level,false); }
    }
    @FunctionalInterface
    public interface Modifier {
        double chance(double configuredChance, Factors factors);
    }
    public static double chance(double configured, Factors factors, Modifier modifier) {
        double adjusted = modifier.chance(configured, factors);
        return Double.isFinite(adjusted) ? Math.clamp(adjusted,0,1) : 0;
    }
}

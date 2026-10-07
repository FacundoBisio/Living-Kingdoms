package dev.livingkingdoms.progression.domain;

/** Frozen spawn modifiers, bounded even at level 100. Percent values are additions to vanilla base. */
public record StatScaling(double healthBonus, double damageBonus, double armorBonus, double speedBonus) {
    public StatScaling {
        check(healthBonus, 1); check(damageBonus, 1); check(armorBonus, 8); check(speedBonus, .15);
    }
    private static void check(double value, double maximum) {
        if (!Double.isFinite(value) || value < 0 || value > maximum) throw new IllegalArgumentException("Invalid persisted scaling");
    }
    public static StatScaling vanilla() { return new StatScaling(0, 0, 0, 0); }
}

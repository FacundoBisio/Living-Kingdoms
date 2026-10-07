package dev.livingkingdoms.progression.domain;

public record StatRules(double healthPerLevel, double healthCap, double damagePerLevel, double damageCap,
                        double armorPerLevel, double armorCap, double speedPerLevel, double speedCap,
                        double eliteStatBonus, double eliteArmorBonus) {
    public StatRules {
        check(healthPerLevel, 1); check(healthCap, 1); check(damagePerLevel, 1); check(damageCap, 1);
        check(armorPerLevel, 8); check(armorCap, 8); check(speedPerLevel, .15); check(speedCap, .15);
        check(eliteStatBonus, .5); check(eliteArmorBonus, 4);
    }
    private static void check(double value, double max) {
        if (!Double.isFinite(value) || value < 0 || value > max) throw new IllegalArgumentException("Invalid stat scaling rule");
    }
    public static StatRules defaults() { return new StatRules(.025, .75, .02, .5, .15, 4, .002, .08, .1, 1); }
    public StatScaling scaling(LevelValue level, boolean elite) {
        int steps = level.value() - 1;
        return new StatScaling(Math.min(healthCap, steps * healthPerLevel + (elite ? eliteStatBonus : 0)),
                Math.min(damageCap, steps * damagePerLevel + (elite ? eliteStatBonus : 0)),
                Math.min(armorCap, steps * armorPerLevel + (elite ? eliteArmorBonus : 0)),
                Math.min(speedCap, steps * speedPerLevel));
    }
}

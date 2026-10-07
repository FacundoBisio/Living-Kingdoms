package dev.livingkingdoms.progression.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Shared bounded distribution with at least two levels when the configured range allows it. */
public final class PartyLevels {
    private PartyLevels() {}
    public static List<LevelValue> generate(RegionalDifficulty region, RegionalRules rules, int count, IntUnaryOperator random) {
        if (count < 1 || count > 16) throw new IllegalArgumentException("Invalid party size");
        List<LevelValue> result = new ArrayList<>();
        int width = rules.memberVariance() * 2 + 1;
        for (int i = 0; i < count; i++) {
            int roll = random.applyAsInt(width);
            if (roll < 0 || roll >= width) throw new IllegalArgumentException("Invalid bounded random value");
            result.add(region.memberLevel(rules, roll - rules.memberVariance()));
        }
        int minimum = region.memberLevel(rules, -rules.memberVariance()).value();
        int maximum = region.memberLevel(rules, rules.memberVariance()).value();
        if (count > 1 && minimum != maximum && result.stream().distinct().count() == 1) {
            result.set(count - 1, new LevelValue(result.getFirst().value() == minimum ? maximum : minimum));
        }
        return List.copyOf(result);
    }
}

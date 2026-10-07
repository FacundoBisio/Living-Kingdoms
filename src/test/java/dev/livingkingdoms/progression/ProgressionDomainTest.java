package dev.livingkingdoms.progression;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionDomainTest {
    private static final double EPS = 1e-9;
    private static RegionalDifficulty region(double distance, long days, int activity, int tier) {
        return RegionalDifficulty.calculate(distance, days, activity, tier, RegionalRules.defaults());
    }
    @Test void reusableLevelHasExplicitSupportedBounds() {
        assertEquals(1, new LevelValue(1).value()); assertEquals(100, new LevelValue(100).value());
        assertThrows(IllegalArgumentException.class, () -> new LevelValue(0));
        assertThrows(IllegalArgumentException.class, () -> new LevelValue(101));
    }
    @Test void distanceChangesOnlyAtBroadInclusiveBands() {
        assertEquals(1, region(0, 0, 0, 0).level().value());
        assertEquals(1, region(1023.999, 0, 0, 0).level().value());
        assertEquals(2, region(1024, 0, 0, 0).level().value());
        assertEquals(10, region(9216, 0, 0, 0).level().value());
    }
    @Test void extremeDistanceSaturatesRatherThanOverflowing() {
        assertEquals(24, region(Double.MAX_VALUE, 0, 0, 0).distanceBonus());
        assertEquals(25, region(Double.MAX_VALUE, 0, 0, 0).level().value());
    }
    @Test void worldAgeIsSlowAndCapped() {
        assertEquals(0, region(0, 9, 0, 0).ageBonus()); assertEquals(1, region(0, 10, 0, 0).ageBonus());
        assertEquals(3, region(0, Long.MAX_VALUE, 0, 0).ageBonus());
        assertEquals(4, region(0, Long.MAX_VALUE, 0, 0).level().value());
    }
    @Test void regionalActivityAndFutureHostileTierAreIndependentAndCapped() {
        var region = region(0, 0, 1000, Integer.MAX_VALUE);
        assertEquals(2, region.activityBonus()); assertEquals(10, region.hostileTierBonus());
        assertEquals(13, region.level().value());
        assertEquals(0, region(0, 0, 3, 0).activityBonus());
    }
    @Test void totalDifficultyCapsAfterContributions() {
        var region = region(Double.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(30, region.level().value());
        assertThrows(IllegalArgumentException.class, () -> region(Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> region(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> region(0, -1, 0, 0));
    }
    @Test void editedRulesCanDisableAgeActivityAndSetDifferentBands() {
        var rules = new RegionalRules(12, 2, 2048, 2, 8, 5, 0, 10, 0, 0, 0, 1);
        var result = RegionalDifficulty.calculate(4096, 1000, 200, 10, rules);
        assertEquals(6, result.level().value()); assertEquals(0, result.ageBonus()); assertEquals(0, result.activityBonus());
    }
    @Test void groupLevelsVaryWithinRegionalWindowForBothFactionTypes() {
        var region = region(9216, 0, 0, 0); var rules = RegionalRules.defaults();
        var first = PartyLevels.generate(region, rules, 8, new Random(12)::nextInt);
        var second = PartyLevels.generate(region, rules, 8, new Random(12)::nextInt);
        assertEquals(first, second); // The shared policy has no separate faction formula.
        assertTrue(first.stream().allMatch(level -> level.value() >= 8 && level.value() <= 12));
        assertTrue(first.stream().distinct().count() > 1);
        assertEquals(8, LevelSummary.of(first).count());
    }
    @Test void identicalRandomRollsStillProduceVarietyWhenPossible() {
        var levels = PartyLevels.generate(region(0, 0, 0, 0), RegionalRules.defaults(), 4, bound -> 0);
        assertEquals(List.of(new LevelValue(1), new LevelValue(1), new LevelValue(1), new LevelValue(3)), levels);
        assertThrows(UnsupportedOperationException.class, () -> levels.clear());
    }
    @Test void configuredMaximumAndZeroVarianceAreRespected() {
        var rules = new RegionalRules(1, 1, 1024, 1, 24, 10, 3, 4, 2, 2, 10, 2);
        assertTrue(PartyLevels.generate(RegionalDifficulty.calculate(99999, 100, 10, 10, rules), rules, 4, n -> n - 1)
                .stream().allMatch(level -> level.value() == 1));
        var zero = new RegionalRules(30, 1, 1024, 1, 24, 10, 3, 4, 2, 2, 10, 0);
        assertEquals(LevelSummary.uniform(4, 10), LevelSummary.of(PartyLevels.generate(region(9216, 0, 0, 0), zero, 4, n -> 0)));
    }
    @Test void partySummaryValidatesRealExtremesAndAccurateAverage() {
        var summary = LevelSummary.of(List.of(new LevelValue(8), new LevelValue(9), new LevelValue(12)));
        assertEquals(8, summary.minimum()); assertEquals(12, summary.maximum()); assertEquals(29.0 / 3, summary.average(), EPS);
        assertThrows(IllegalArgumentException.class, () -> new LevelSummary(1, 5, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> new LevelSummary(3, 4, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> PartyLevels.generate(region(0, 0, 0, 0), RegionalRules.defaults(), 0, n -> 0));
    }
    @Test void levelOneIsVanillaAndModerateLevelsRemainConservative() {
        var rules = StatRules.defaults(); assertEquals(StatScaling.vanilla(), rules.scaling(new LevelValue(1), false));
        var five = rules.scaling(new LevelValue(5), false); assertEquals(.10, five.healthBonus(), EPS); assertEquals(.08, five.damageBonus(), EPS);
        var ten = rules.scaling(new LevelValue(10), false); assertEquals(.225, ten.healthBonus(), EPS); assertEquals(.18, ten.damageBonus(), EPS);
    }
    @Test void highLevelsAndElitesCannotExceedStatCaps() {
        var scaling = StatRules.defaults().scaling(new LevelValue(100), true);
        assertEquals(.75, scaling.healthBonus(), EPS); assertEquals(.5, scaling.damageBonus(), EPS);
        assertEquals(4, scaling.armorBonus(), EPS); assertEquals(.08, scaling.speedBonus(), EPS);
    }
    @Test void statConfigurationCanDisableBonusesAndPersistedLimitsRejectBadValues() {
        var zero = new StatRules(0, 0, 0, 0, 0, 0, 0, 0, .1, 1);
        assertEquals(StatScaling.vanilla(), zero.scaling(new LevelValue(30), true));
        assertThrows(IllegalArgumentException.class, () -> new StatScaling(2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StatScaling(0, Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StatScaling(0, 0, 0, .2));
    }
    @Test void equipmentThresholdsApplyToHostilesAndExcludeAlliedCivilianPolicies() {
        var rules = EquipmentRules.defaults();
        for (var faction : List.of(Faction.PILLAGER, Faction.UNDEAD, Faction.BANDIT)) {
            assertEquals(EquipmentRules.Tier.BASIC, rules.tier(faction, new LevelValue(4)));
            assertEquals(EquipmentRules.Tier.LEATHER, rules.tier(faction, new LevelValue(5)));
            assertEquals(EquipmentRules.Tier.CHAIN, rules.tier(faction, new LevelValue(10)));
            assertEquals(EquipmentRules.Tier.IRON, rules.tier(faction, new LevelValue(20)));
        }
        assertEquals(EquipmentRules.Tier.BASIC, rules.tier(Faction.ALLIED_KINGDOM, new LevelValue(100)));
        assertThrows(IllegalArgumentException.class, () -> new EquipmentRules(10, 5, 20, 15, .1));
    }
    @Test void smallEnchantmentProbabilityHonorsThresholdAndExactChance() {
        var rules = EquipmentRules.defaults();
        assertFalse(rules.enchant(new LevelValue(14), 0)); assertTrue(rules.enchant(new LevelValue(15), .079));
        assertFalse(rules.enchant(new LevelValue(15), .08));
        assertThrows(IllegalArgumentException.class, () -> rules.enchant(new LevelValue(1), Double.NaN));
    }
    @Test void eliteThresholdChanceAndDisabledModeAreDeterministic() {
        assertFalse(ProgressionService.isElite(19, 20, .05, 0)); assertTrue(ProgressionService.isElite(20, 20, .05, .049));
        assertFalse(ProgressionService.isElite(20, 20, .05, .05)); assertFalse(ProgressionService.isElite(30, 20, 0, 0));
        assertTrue(ProgressionService.isElite(20, 20, 1, .99));
        assertThrows(IllegalArgumentException.class, () -> ProgressionService.isElite(20, 20, .05, 1));
    }
}

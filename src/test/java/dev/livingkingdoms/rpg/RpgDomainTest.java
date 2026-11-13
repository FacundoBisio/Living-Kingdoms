package dev.livingkingdoms.rpg;

import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.quest.expansion.domain.QuestCategory;
import dev.livingkingdoms.quest.expansion.domain.QuestDifficulty;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.rpg.domain.ChallengeModifier;
import dev.livingkingdoms.rpg.domain.ChallengeTier;
import dev.livingkingdoms.rpg.domain.Happiness;
import dev.livingkingdoms.rpg.domain.Trait;
import dev.livingkingdoms.rpg.domain.QuestBonus;
import dev.livingkingdoms.rpg.domain.RallyPolicy;
import dev.livingkingdoms.rpg.domain.RefinementRules;
import dev.livingkingdoms.structure.BuildingKind;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RpgDomainTest {
    @Test void refinementTurnsDiamondSevenIntoNine() {
        assertEquals(0, RefinementRules.damageBonus(0));
        assertEquals(2.0, 7 + RefinementRules.damageBonus(RefinementRules.MAX_LEVEL) - 7, 1e-9);
        assertEquals(9.0, 7 + RefinementRules.damageBonus(5), 1e-9);
        assertEquals(RefinementRules.damageBonus(5), RefinementRules.damageBonus(99), 1e-9, "Levels clamp at the cap");
        assertEquals(0, RefinementRules.damageBonus(-3), 1e-9);
    }

    @Test void refinementCostsEscalateAndStopAtCap() {
        int total = 0;
        for (int level = 0; level < RefinementRules.MAX_LEVEL; level++) {
            assertTrue(RefinementRules.canRefine(level));
            int cost = RefinementRules.experienceCost(level);
            assertTrue(cost > 0); total += cost;
            if (level > 0) assertTrue(cost > RefinementRules.experienceCost(level - 1));
        }
        assertEquals(15, total, "1+2+3+4+5 levels for a full refinement");
        assertFalse(RefinementRules.canRefine(RefinementRules.MAX_LEVEL));
        assertThrows(IllegalArgumentException.class, () -> RefinementRules.experienceCost(RefinementRules.MAX_LEVEL));
    }

    @Test void mainChainBonusesGrowAndEndWithATome() {
        assertTrue(QuestBonus.of(QuestTemplate.FIRST_MEETING, QuestDifficulty.VERY_EASY).empty());
        int previous = -1;
        for (var template : QuestTemplate.values()) {
            if (template.category() != QuestCategory.MAIN) continue;
            int xp = QuestBonus.of(template, QuestDifficulty.VERY_EASY).experience();
            assertTrue(xp >= previous, "Main chain experience never decreases: " + template);
            previous = xp;
        }
        assertEquals(1, QuestBonus.of(QuestTemplate.MAIN_RETURN, QuestDifficulty.NORMAL).tomes());
        assertTrue(QuestBonus.of(QuestTemplate.MAIN_IRON, QuestDifficulty.NORMAL).whetstones() >= 1);
    }

    @Test void dynamicBonusesScaleWithDifficulty() {
        var easy = QuestBonus.of(QuestTemplate.FOOD_REQUEST, QuestDifficulty.VERY_EASY);
        var hard = QuestBonus.of(QuestTemplate.FOOD_REQUEST, QuestDifficulty.VERY_HARD);
        assertTrue(hard.experience() > easy.experience());
        assertTrue(hard.whetstones() > easy.whetstones());
        assertEquals(0, easy.tomes()); assertEquals(1, hard.tomes());
        assertTrue(QuestBonus.of(QuestTemplate.BANDIT_REQUEST, QuestDifficulty.VERY_EASY).whetstones() >= 1, "Combat always pays a whetstone");
        assertThrows(IllegalArgumentException.class, () -> new QuestBonus(-1, 0, 0));
    }

    @Test void happinessIsBoundedAndNamesTheWorstIssue() {
        var fresh = Happiness.of(3, 4, true, 0, 1);
        assertEquals(Happiness.Band.CONTENT, fresh.band(), "A fed, housed hamlet is content, not yet happy");
        assertEquals(Happiness.Issue.NO_TAVERN, fresh.issue());
        var thriving = Happiness.of(10, 12, true, 3, 20);
        assertEquals(100, thriving.value());
        assertEquals(Happiness.Band.HAPPY, thriving.band());
        assertEquals(Happiness.Issue.NONE, thriving.issue());
        var starving = Happiness.of(10, 2, false, 0, 0);
        assertEquals(0, starving.value());
        assertEquals(Happiness.Band.UNHAPPY, starving.band());
        assertEquals(Happiness.Issue.NO_FOOD, starving.issue(), "Food outranks beds");
        assertEquals(Happiness.Issue.NO_BEDS, Happiness.of(10, 2, true, 3, 5).issue());
        assertEquals(Happiness.Issue.NONE, Happiness.of(5, 4, true, 1, 5).issue(), "The Mayor needs no house bed");
        assertTrue(Happiness.of(4, 4, false, 0, 1).value() < Happiness.of(4, 4, true, 0, 1).value());
        assertThrows(IllegalArgumentException.class, () -> Happiness.of(-1, 0, true, 0, 0));
    }

    @Test void traitsAreStableSpreadAndBounded() {
        var seen = java.util.EnumSet.noneOf(Trait.class);
        for (int i = 0; i < 64; i++) {
            var id = new java.util.UUID(i * 7919L, i * 104729L + 3);
            assertEquals(Trait.of(id), Trait.of(new java.util.UUID(id.getMostSignificantBits(), id.getLeastSignificantBits())));
            seen.add(Trait.of(id));
        }
        assertEquals(java.util.EnumSet.allOf(Trait.class), seen, "Every trait appears among residents");
        for (var band : Happiness.Band.values()) for (var trait : Trait.values()) {
            double work = Trait.work(band, trait);
            assertTrue(work >= Trait.MIN_WORK && work <= Trait.MAX_WORK, band + "/" + trait + " = " + work);
        }
        assertTrue(Trait.work(Happiness.Band.HAPPY, Trait.HARD_WORKING) > Trait.work(Happiness.Band.CONTENT, Trait.BRAVE));
        assertTrue(Trait.work(Happiness.Band.UNHAPPY, Trait.LAZY) < Trait.work(Happiness.Band.CONTENT, Trait.BRAVE));
    }

    @Test void challengeModifiersAreBoundedAndPayPerHandicap() {
        assertTrue(ChallengeModifier.valid(0) && ChallengeModifier.of(0).isEmpty());
        int two = ChallengeModifier.HORDE.bit() | ChallengeModifier.TIGHT.bit();
        assertEquals(java.util.Set.of(ChallengeModifier.HORDE, ChallengeModifier.TIGHT), ChallengeModifier.of(two));
        assertEquals(1, ChallengeModifier.extraWhetstones(ChallengeModifier.NO_HEALING.bit()));
        assertEquals(0, ChallengeModifier.extraTomes(ChallengeModifier.NO_HEALING.bit()));
        assertEquals(2, ChallengeModifier.extraWhetstones(two));
        assertEquals(1, ChallengeModifier.extraTomes(two));
        int all = 0;
        for (var modifier : ChallengeModifier.values()) all |= modifier.bit();
        assertFalse(ChallengeModifier.valid(all), "More than the allowed count is refused");
        assertFalse(ChallengeModifier.valid(1 << ChallengeModifier.values().length), "Unknown bits are refused");
        assertFalse(ChallengeModifier.valid(-1));
        assertThrows(IllegalArgumentException.class, () -> ChallengeModifier.of(-1));
    }

    @Test void challengeTiersEscalate() {
        ChallengeTier previous = null;
        for (var tier : ChallengeTier.values()) {
            assertTrue(tier.totalEnemies() > 0);
            assertEquals(tier, ChallengeTier.fromId(tier.id()));
            for (int wave = 1; wave < tier.waves(); wave++) assertTrue(tier.enemies(wave) > tier.enemies(wave - 1), "Later waves are larger");
            if (previous != null) {
                assertTrue(tier.totalEnemies() > previous.totalEnemies());
                assertTrue(tier.emeralds() > previous.emeralds() && tier.whetstones() > previous.whetstones());
                assertTrue(tier.timeLimitTicks() >= previous.timeLimitTicks());
            }
            previous = tier;
        }
        assertEquals(ChallengeTier.Reward.CHAMPION_SWORD, ChallengeTier.WARLORD.reward());
        assertFalse(ChallengeTier.SKIRMISH.boss());
        assertThrows(IllegalArgumentException.class, () -> ChallengeTier.SKIRMISH.enemies(ChallengeTier.SKIRMISH.waves()));
        assertThrows(IllegalArgumentException.class, () -> ChallengeTier.fromId("nope"));
    }

    @Test void ralliesExpireAndTargetOnlyTheirCitizen() {
        UUID one = UUID.randomUUID(), other = UUID.randomUUID();
        var all = RallyPolicy.call("minecraft:overworld", 0, 64, 0, 100, RallyPolicy.DEFAULT_DURATION_TICKS, null);
        assertTrue(RallyPolicy.effective(all, 100, one, "minecraft:overworld").isPresent());
        assertTrue(RallyPolicy.effective(all, 100 + RallyPolicy.DEFAULT_DURATION_TICKS, one, "minecraft:overworld").isEmpty(), "Expired");
        assertTrue(RallyPolicy.effective(all, 100, one, "minecraft:the_nether").isEmpty(), "Dimension bound");
        var single = RallyPolicy.call("minecraft:overworld", 0, 64, 0, 0, 200, one);
        assertTrue(RallyPolicy.effective(single, 10, one, "minecraft:overworld").isPresent());
        assertTrue(RallyPolicy.effective(single, 10, other, "minecraft:overworld").isEmpty());
        assertTrue(RallyPolicy.withinReach(60, 60)); assertFalse(RallyPolicy.withinReach(100, 10));
        assertThrows(IllegalArgumentException.class, () -> RallyPolicy.call("x", 0, 0, 0, 0, 5, null));
    }

    @Test void foundingBuildsTheHouseBeforeTheTownHall() {
        assertEquals(BuildingKind.HOUSE, ConstructionService.foundingNext(false, false, true));
        assertEquals(BuildingKind.TOWN_HALL, ConstructionService.foundingNext(false, true, true));
        assertEquals(BuildingKind.TOWN_HALL, ConstructionService.foundingNext(false, false, false), "Legacy order stays available");
        assertEquals(BuildingKind.HOUSE, ConstructionService.foundingNext(true, false, false));
        // A world that already finished its Town Hall under the old order still gets its House next.
        assertEquals(BuildingKind.HOUSE, ConstructionService.foundingNext(true, false, true));
    }
}

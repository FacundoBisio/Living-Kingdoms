package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelSummary;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuestExpansionDomainTest {
    private static final UUID SETTLEMENT = new UUID(10, 11);
    private static final QuestRules DEFAULT = QuestRules.defaults();

    @Test
    void firstChapterIsOrderedAndOnlyTheNextQuestUnlocks() {
        assertEquals(List.of(QuestTemplate.FIRST_MEETING, QuestTemplate.MAIN_IRON,
                QuestTemplate.MAIN_PATROL, QuestTemplate.MAIN_RETURN), QuestCatalog.mainChain());
        Set<QuestTemplate> completed = new HashSet<>();
        for (QuestTemplate next : QuestCatalog.mainChain()) {
            assertEquals(next, QuestCatalog.nextMain(completed).orElseThrow());
            assertTrue(QuestCatalog.prerequisitesMet(next, completed));
            for (QuestTemplate later : QuestCatalog.mainChain())
                if (later.order() > next.order()) assertFalse(QuestCatalog.prerequisitesMet(later, completed));
            assertEquals("first_steps", next.chapter());
            completed.add(next);
        }
        assertTrue(QuestCatalog.nextMain(completed).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> QuestCatalog.mainChain().clear());
    }

    @Test
    void prerequisitesRejectACompletionGapAndDynamicRequestsHaveNoPrerequisite() {
        assertFalse(QuestCatalog.prerequisitesMet(QuestTemplate.MAIN_RETURN, Set.of(QuestTemplate.MAIN_PATROL)));
        assertEquals(QuestTemplate.MAIN_IRON, QuestCatalog.prerequisite(QuestTemplate.MAIN_PATROL).orElseThrow());
        assertTrue(QuestCatalog.prerequisite(QuestTemplate.FIRST_MEETING).isEmpty());
        assertTrue(QuestCatalog.prerequisitesMet(QuestTemplate.UNDEAD_REQUEST, Set.of()));
        assertEquals(QuestTemplate.FIRST_MEETING, QuestCatalog.nextMain(Set.of(QuestTemplate.MAIN_IRON)).orElseThrow());
    }

    @Test
    void persistedTemplateAndResourceIdentitiesDoNotDependOnTranslatedTitles() {
        for (QuestTemplate template : QuestTemplate.values()) {
            assertEquals(template, QuestTemplate.fromId(template.id()));
            assertTrue(template.titleKey().endsWith(template.id() + ".title"));
            assertEquals(template.category() == QuestCategory.MAIN, template.order() > 0);
        }
        for (ResourceKind resource : ResourceKind.values()) assertEquals(resource, ResourceKind.fromId(resource.id()));
        assertThrows(IllegalArgumentException.class, () -> QuestTemplate.fromId("Iron Shortage"));
        assertThrows(IllegalArgumentException.class, () -> ResourceKind.fromId("minecraft:oak_log"));
    }

    @Test
    void difficultyUsesSharedLevelBoundariesWithoutDuplicatingRegionalCalculation() {
        int[] levels = {1, 3, 4, 7, 8, 12, 13, 20, 21, 100};
        QuestDifficulty[] expected = {QuestDifficulty.VERY_EASY, QuestDifficulty.VERY_EASY,
                QuestDifficulty.EASY, QuestDifficulty.EASY, QuestDifficulty.NORMAL, QuestDifficulty.NORMAL,
                QuestDifficulty.HARD, QuestDifficulty.HARD, QuestDifficulty.VERY_HARD, QuestDifficulty.VERY_HARD};
        for (int i = 0; i < levels.length; i++) assertEquals(expected[i], QuestDifficulty.fromLevel(new LevelValue(levels[i])));
    }

    @Test
    void sharedResourceObjectiveSupportsAllVariantsAndImmutableRequirements() {
        assertEquals(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT, 16)),
                QuestObjectives.forResourceTemplate(QuestTemplate.IRON_REQUEST, DEFAULT).requirements());
        assertEquals(List.of(new ResourceRequirement(ResourceKind.WHEAT, 32)),
                QuestObjectives.forResourceTemplate(QuestTemplate.FOOD_REQUEST, DEFAULT).requirements());
        assertEquals(List.of(new ResourceRequirement(ResourceKind.LOGS, 32), new ResourceRequirement(ResourceKind.STONE, 32)),
                QuestObjectives.forResourceTemplate(QuestTemplate.BUILDING_REQUEST, DEFAULT).requirements());
        List<ResourceRequirement> mutable = new ArrayList<>(List.of(new ResourceRequirement(ResourceKind.WHEAT, 32)));
        QuestObjective.Resource objective = new QuestObjective.Resource(mutable);
        mutable.clear();
        assertEquals(1, objective.requirements().size());
        assertThrows(UnsupportedOperationException.class, () -> objective.requirements().clear());
    }

    @Test
    void resourceObjectivesRejectEmptyDuplicateAndUnboundedRequirements() {
        assertThrows(IllegalArgumentException.class, () -> new QuestObjective.Resource(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRequirement(ResourceKind.STONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRequirement(ResourceKind.STONE, 2305));
        assertThrows(IllegalArgumentException.class, () -> new QuestObjective.Resource(List.of(
                new ResourceRequirement(ResourceKind.STONE, 2), new ResourceRequirement(ResourceKind.STONE, 3))));
        assertThrows(IllegalArgumentException.class, () -> QuestObjectives.forResourceTemplate(QuestTemplate.MAIN_PATROL, DEFAULT));
    }

    @Test
    void resourceQuantitiesAndRewardRulesAreConfigurableSnapshots() {
        QuestRules configured = new QuestRules(2, 1000, 2000, 128, 25, 51, 63, 12,
                2, 3, 8, 9, 10, 11, 2, 4, 32, 50);
        assertEquals(25, QuestObjectives.forResourceTemplate(QuestTemplate.IRON_REQUEST, configured).requirements().getFirst().count());
        assertEquals(51, QuestObjectives.forResourceTemplate(QuestTemplate.FOOD_REQUEST, configured).requirements().getFirst().count());
        assertEquals(List.of(new ResourceRequirement(ResourceKind.LOGS, 63), new ResourceRequirement(ResourceKind.STONE, 12)),
                QuestObjectives.forResourceTemplate(QuestTemplate.BUILDING_REQUEST, configured).requirements());
        assertEquals(new QuestRewards(6, 11), QuestRewardPolicy.forTemplate(QuestTemplate.FOOD_REQUEST, QuestDifficulty.NORMAL, configured));
        assertEquals(new QuestRewards(10, 11), QuestRewardPolicy.forTemplate(QuestTemplate.MAIN_RETURN, QuestDifficulty.VERY_HARD, configured));
    }

    @Test
    void dynamicRewardsScaleByDifficultyWhileMainChainAvoidsDuplicateIronAndPartyPayouts() {
        for (QuestDifficulty difficulty : QuestDifficulty.values()) {
            assertEquals(new QuestRewards(4 + difficulty.rewardSteps(), 5 + difficulty.rewardSteps()),
                    QuestRewardPolicy.forTemplate(QuestTemplate.FOOD_REQUEST, difficulty, DEFAULT));
            assertEquals(new QuestRewards(4 + difficulty.rewardSteps(), 3 + difficulty.rewardSteps()),
                    QuestRewardPolicy.forTemplate(QuestTemplate.UNDEAD_REQUEST, difficulty, DEFAULT));
            assertEquals(new QuestRewards(0, 0), QuestRewardPolicy.forTemplate(QuestTemplate.MAIN_IRON, difficulty, DEFAULT));
            assertEquals(new QuestRewards(0, 0), QuestRewardPolicy.forTemplate(QuestTemplate.MAIN_PATROL, difficulty, DEFAULT));
        }
        assertEquals(new QuestRewards(6, 5), QuestRewardPolicy.forTemplate(QuestTemplate.MAIN_RETURN, QuestDifficulty.EASY, DEFAULT));
    }

    @Test
    void rewardCapsAndZeroRewardsRemainValidAtMaximumConfiguredInputs() {
        QuestRules extreme = new QuestRules(3, 48000, 48000, 256, 16, 32, 32, 32,
                2304, 1000000, 2304, 1000000, 2304, 1000000, 2304, 1000000, 20, 40);
        assertEquals(new QuestRewards(20, 40), QuestRewardPolicy.forTemplate(QuestTemplate.PILLAGER_REQUEST, QuestDifficulty.VERY_HARD, extreme));
        assertEquals(new QuestRewards(0, 0), new QuestRewards(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestRewards(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestRewards(2305, 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestRewards(0, 1000001));
    }

    @Test
    void rotationRulesRejectUnsafeCountsClocksAndRequirements() {
        assertThrows(IllegalArgumentException.class, () -> rulesWithCount(1));
        assertThrows(IllegalArgumentException.class, () -> rulesWithCount(5));
        assertThrows(IllegalArgumentException.class, () -> new QuestRules(3, 0, 48000, 256, 16, 32, 32, 32,
                4, 5, 4, 3, 6, 5, 1, 1, 32, 50));
        assertThrows(IllegalArgumentException.class, () -> new QuestRules(3, 48000, 48000, 4097, 16, 32, 32, 32,
                4, 5, 4, 3, 6, 5, 1, 1, 32, 50));
        assertThrows(IllegalArgumentException.class, () -> new QuestRules(3, 48000, 48000, 256, 0, 32, 32, 32,
                4, 5, 4, 3, 6, 5, 1, 1, 32, 50));
    }

    @Test
    void noHostileMetadataProducesThreeDistinctUsefulResourceOffers() {
        List<QuestInstance> quests = QuestGenerator.generate(SETTLEMENT, new LevelValue(8), List.of(), Map.of(), DEFAULT, new Random(4), 100);
        assertEquals(3, quests.size());
        assertEquals(Set.of(QuestTemplate.IRON_REQUEST, QuestTemplate.FOOD_REQUEST, QuestTemplate.BUILDING_REQUEST),
                new HashSet<>(quests.stream().map(QuestInstance::template).toList()));
        assertEquals(3, new HashSet<>(quests.stream().map(QuestInstance::id).toList()).size());
        for (QuestInstance quest : quests) {
            assertEquals(QuestState.AVAILABLE, quest.state());
            assertEquals(QuestDifficulty.NORMAL, quest.difficulty());
            assertEquals(48100, quest.expiresAt());
            assertEquals(SETTLEMENT, quest.source().settlementId());
        }
        assertEquals(3, QuestGenerator.generate(SETTLEMENT, new LevelValue(1), List.of(), Map.of(), rulesWithCount(4), new Random(4), 100).size());
    }

    @Test
    void nearbyHostilesIncreaseCombatWeightAndAssociateActualUuidWithPartyAverageLevel() {
        UUID pillager = new UUID(0, 1), undead = new UUID(0, 2);
        List<QuestGenerator.PartyTarget> targets = List.of(
                new QuestGenerator.PartyTarget(pillager, Faction.PILLAGER, PartyType.PILLAGER_PATROL, new LevelSummary(2, 15, 7, 8)),
                new QuestGenerator.PartyTarget(undead, Faction.UNDEAD, PartyType.UNDEAD_HORDE, LevelSummary.uniform(6, 21)));
        List<QuestGenerator.Candidate> candidates = QuestGenerator.candidates(new LevelValue(2), targets, Map.of(), DEFAULT);
        assertEquals(5, candidates.size());
        QuestGenerator.Candidate p = candidates.stream().filter(c -> c.template() == QuestTemplate.PILLAGER_REQUEST).findFirst().orElseThrow();
        QuestGenerator.Candidate u = candidates.stream().filter(c -> c.template() == QuestTemplate.UNDEAD_REQUEST).findFirst().orElseThrow();
        assertEquals(new LevelValue(8), p.recommendedLevel());
        assertEquals(new LevelValue(21), u.recommendedLevel());
        assertEquals(pillager, ((QuestObjective.Party) p.objective()).partyId());
        assertEquals(undead, ((QuestObjective.Party) u.objective()).partyId());
        assertTrue(p.weight() > candidates.getFirst().weight());
        assertTrue(u.weight() > p.weight());
    }

    @Test
    void shortageContextAddsWeightAndDoesNotInventSettlementResourceSimulation() {
        List<QuestGenerator.Candidate> neutral = QuestGenerator.candidates(new LevelValue(1), List.of(), Map.of(), DEFAULT);
        List<QuestGenerator.Candidate> hungry = QuestGenerator.candidates(new LevelValue(1), List.of(), Map.of(ResourceKind.WHEAT, 12), DEFAULT);
        assertEquals(3, neutral.get(1).weight());
        assertEquals(15, hungry.get(1).weight());
        assertEquals(neutral.getFirst().weight(), hungry.getFirst().weight());
        assertThrows(IllegalArgumentException.class, () -> QuestGenerator.candidates(new LevelValue(1), List.of(), Map.of(ResourceKind.WHEAT, -1), DEFAULT));
    }

    @Test
    void generationIsDeterministicAcrossMetadataOrderingAndNeverDuplicatesPartyTargets() {
        var a = new QuestGenerator.PartyTarget(new UUID(0, 1), Faction.PILLAGER, PartyType.PILLAGER_PATROL, LevelSummary.uniform(4, 5));
        var b = new QuestGenerator.PartyTarget(new UUID(0, 2), Faction.UNDEAD, PartyType.UNDEAD_HORDE, LevelSummary.uniform(6, 6));
        List<QuestInstance> first = QuestGenerator.generate(SETTLEMENT, new LevelValue(3), List.of(a, b, a), Map.of(), rulesWithCount(4), new Random(25), 60);
        List<QuestInstance> reordered = QuestGenerator.generate(SETTLEMENT, new LevelValue(3), List.of(b, a), Map.of(), rulesWithCount(4), new Random(25), 60);
        assertEquals(first, reordered);
        assertEquals(4, first.size());
        assertEquals(first.stream().map(QuestInstance::objective).distinct().count(), first.size());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }

    @Test
    void partyObjectiveRequiresHostileFactionTypeMatchAndRetainsUuid() {
        UUID target = new UUID(7, 8);
        assertEquals(target, new QuestObjective.Party(target, Faction.UNDEAD, PartyType.UNDEAD_HORDE).partyId());
        assertThrows(IllegalArgumentException.class, () -> new QuestObjective.Party(target, Faction.PILLAGER, PartyType.UNDEAD_HORDE));
        assertThrows(IllegalArgumentException.class, () -> new QuestObjective.Party(target, Faction.ALLIED_KINGDOM, PartyType.PILLAGER_PATROL));
    }

    @Test
    void lifecycleRequiresAcceptanceAndObjectiveReadinessBeforeOneCompletion() {
        QuestInstance offered = resourceQuest(QuestState.AVAILABLE, false, 100, 200);
        assertThrows(IllegalStateException.class, offered::ready);
        assertThrows(IllegalStateException.class, () -> offered.withState(QuestState.COMPLETED));
        QuestInstance active = offered.withState(QuestState.ACTIVE);
        assertThrows(IllegalArgumentException.class, () -> active.withState(QuestState.COMPLETED));
        QuestInstance ready = active.ready();
        assertTrue(ready.objectiveSatisfied());
        assertFalse(active.objectiveSatisfied());
        QuestInstance completed = ready.withState(QuestState.COMPLETED);
        assertSame(completed, completed.withState(QuestState.COMPLETED));
        assertThrows(IllegalStateException.class, () -> completed.withState(QuestState.ACTIVE));
        assertThrows(IllegalStateException.class, completed::ready);
    }

    @Test
    void dynamicExpirationAndStaleTargetFailureDoNotPretendAPlayerAccepted() {
        QuestInstance offered = resourceQuest(QuestState.AVAILABLE, false, 100, 200);
        assertFalse(offered.expiresAtTick(199));
        assertTrue(offered.expiresAtTick(200));
        assertEquals(QuestState.EXPIRED, offered.withState(QuestState.EXPIRED).state());
        assertEquals(QuestState.FAILED, offered.withState(QuestState.FAILED).state());
        assertEquals(QuestState.EXPIRED, offered.withState(QuestState.ACTIVE).ready().withState(QuestState.EXPIRED).state());
        assertThrows(IllegalArgumentException.class, () -> resourceQuest(QuestState.AVAILABLE, false, 200, 199));
        assertThrows(IllegalArgumentException.class, () -> QuestGenerator.generate(SETTLEMENT, new LevelValue(1), List.of(), Map.of(), DEFAULT, new Random(4), -1));
    }

    @Test
    void mainQuestsCannotExpireAndRequireMatchingObjectiveSourceAndDifficulty() {
        QuestTemplate template = QuestTemplate.FIRST_MEETING;
        QuestSource source = new QuestSource(SETTLEMENT, new UUID(4, 5), QuestSourceRole.MAYOR);
        QuestInstance first = new QuestInstance(new UUID(6, 7), template, source, new QuestObjective.Meet(QuestSourceRole.MAYOR),
                new LevelValue(1), QuestDifficulty.VERY_EASY, new QuestRewards(0, 0), QuestState.AVAILABLE, false, 0, -1);
        assertFalse(first.expiresAtTick(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> first.withState(QuestState.EXPIRED));
        assertThrows(IllegalArgumentException.class, () -> new QuestInstance(first.id(), template, source,
                new QuestObjective.Return(), first.recommendedLevel(), first.difficulty(), first.rewards(), first.state(), false, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new QuestInstance(first.id(), template,
                new QuestSource(SETTLEMENT, null, QuestSourceRole.FARMER), first.objective(), first.recommendedLevel(),
                first.difficulty(), first.rewards(), first.state(), false, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new QuestInstance(first.id(), template, source, first.objective(),
                first.recommendedLevel(), QuestDifficulty.HARD, first.rewards(), first.state(), false, 0, -1));
    }

    private static QuestRules rulesWithCount(int count) {
        return new QuestRules(count, 48000, 48000, 256, 16, 32, 32, 32, 4, 5, 4, 3, 6, 5, 1, 1, 32, 50);
    }
    private static QuestInstance resourceQuest(QuestState state, boolean satisfied, long created, long expires) {
        return new QuestInstance(new UUID(4, 5), QuestTemplate.FOOD_REQUEST,
                new QuestSource(SETTLEMENT, null, QuestSourceRole.FARMER),
                QuestObjectives.forResourceTemplate(QuestTemplate.FOOD_REQUEST, DEFAULT),
                new LevelValue(1), QuestDifficulty.VERY_EASY, new QuestRewards(4, 5), state, satisfied, created, expires);
    }
}

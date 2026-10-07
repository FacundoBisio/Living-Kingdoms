package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelSummary;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Pure weighted selection. The caller supplies indexed metadata, a persisted generation seed and clock. */
public final class QuestGenerator {
    private QuestGenerator() {}
    public record PartyTarget(UUID partyId, Faction faction, PartyType partyType, LevelSummary levels) {
        public PartyTarget {
            new QuestObjective.Party(partyId, faction, partyType);
            Objects.requireNonNull(levels, "levels");
        }
    }
    public record Candidate(QuestTemplate template, QuestObjective objective, LevelValue recommendedLevel, int weight) {
        public Candidate {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(objective, "objective");
            Objects.requireNonNull(recommendedLevel, "recommendedLevel");
            if (template.category() != QuestCategory.DYNAMIC || weight < 1 || weight > 1000)
                throw new IllegalArgumentException("Invalid dynamic quest candidate");
        }
    }

    /** Resource requests remain useful when no active hostile group exists; shortages are future caller inputs. */
    public static List<Candidate> candidates(LevelValue regionalLevel, List<PartyTarget> nearbyTargets,
            Map<ResourceKind, Integer> shortageWeights, QuestRules rules) {
        Objects.requireNonNull(regionalLevel, "regionalLevel");
        Objects.requireNonNull(nearbyTargets, "nearbyTargets");
        Objects.requireNonNull(shortageWeights, "shortageWeights");
        Objects.requireNonNull(rules, "rules");
        List<Candidate> result = new ArrayList<>();
        for (QuestTemplate template : List.of(QuestTemplate.IRON_REQUEST, QuestTemplate.FOOD_REQUEST,
                QuestTemplate.BUILDING_REQUEST)) {
            QuestObjective.Resource objective = QuestObjectives.forResourceTemplate(template, rules);
            int weight = 3;
            for (ResourceRequirement requirement : objective.requirements()) {
                int shortage = shortageWeights.getOrDefault(requirement.resource(), 0);
                if (shortage < 0) throw new IllegalArgumentException("Shortage weights cannot be negative");
                weight += Math.min(100, shortage);
            }
            result.add(new Candidate(template, objective, regionalLevel, weight));
        }
        Set<UUID> seenParties = new HashSet<>();
        // Stable metadata ordering makes the same seed independent of hash-map iteration order.
        for (PartyTarget target : nearbyTargets.stream().sorted(Comparator.comparing(t -> t.partyId().toString())).toList()) {
            if (!seenParties.add(target.partyId())) continue;
            QuestTemplate template = switch (target.partyType()) {
                case PILLAGER_PATROL -> QuestTemplate.PILLAGER_REQUEST;
                case UNDEAD_HORDE -> QuestTemplate.UNDEAD_REQUEST;
            };
            LevelValue recommended = new LevelValue((int) Math.ceil(target.levels().average()));
            int weight = 8 + Math.min(4, recommended.value() / 5);
            result.add(new Candidate(template, new QuestObjective.Party(target.partyId(), target.faction(), target.partyType()),
                    recommended, weight));
        }
        return List.copyOf(result);
    }

    public static List<QuestInstance> generate(UUID settlementId, LevelValue regionalLevel,
            List<PartyTarget> nearbyTargets, Map<ResourceKind, Integer> shortageWeights,
            QuestRules rules, RandomGenerator random, long now) {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(random, "random");
        if (now < 0) throw new IllegalArgumentException("Negative quest creation clock");
        long expiresAt = Math.addExact(now, rules.expirationTicks());
        List<Candidate> remaining = new ArrayList<>(candidates(regionalLevel, nearbyTargets, shortageWeights, rules));
        List<QuestInstance> generated = new ArrayList<>();
        Set<UUID> generatedIds = new HashSet<>();
        int count = Math.min(rules.dynamicCount(), remaining.size());
        for (int i = 0; i < count; i++) {
            int total = 0;
            for (Candidate candidate : remaining) total = Math.addExact(total, candidate.weight());
            int roll = random.nextInt(total), selected = 0;
            while (roll >= remaining.get(selected).weight()) roll -= remaining.get(selected++).weight();
            Candidate candidate = remaining.remove(selected);
            UUID id = new UUID(random.nextLong(), random.nextLong());
            // A deterministic bounded fallback also protects callers supplying a degenerate RandomGenerator.
            while (!generatedIds.add(id)) id = new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits() + 1);
            QuestDifficulty difficulty = QuestDifficulty.fromLevel(candidate.recommendedLevel());
            generated.add(new QuestInstance(id, candidate.template(), new QuestSource(settlementId, null, candidate.template().sourceRole()),
                    candidate.objective(), candidate.recommendedLevel(), difficulty,
                    QuestRewardPolicy.forTemplate(candidate.template(), difficulty, rules), QuestState.AVAILABLE,
                    false, now, expiresAt));
        }
        return List.copyOf(generated);
    }
}

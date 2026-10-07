package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import java.util.Objects;
import java.util.UUID;

/** An immutable server-owned snapshot. Per-player ownership belongs to the persistence aggregate. */
public record QuestInstance(UUID id, QuestTemplate template, QuestSource source, QuestObjective objective,
        LevelValue recommendedLevel, QuestDifficulty difficulty, QuestRewards rewards, QuestState state,
        boolean objectiveSatisfied, long createdAt, long expiresAt) {
    public QuestInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(recommendedLevel, "recommendedLevel");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(rewards, "rewards");
        Objects.requireNonNull(state, "state");
        if (createdAt < 0 || (template.category() == QuestCategory.MAIN ? expiresAt != -1 : expiresAt < createdAt))
            throw new IllegalArgumentException("Invalid quest clocks");
        if (template.category() == QuestCategory.MAIN && state == QuestState.EXPIRED)
            throw new IllegalArgumentException("Main quests cannot expire");
        if (source.role() != template.sourceRole()) throw new IllegalArgumentException("Quest source role mismatch");
        if (difficulty != QuestDifficulty.fromLevel(recommendedLevel)) throw new IllegalArgumentException("Quest difficulty does not match recommended level");
        if ((state == QuestState.AVAILABLE && objectiveSatisfied) || (state == QuestState.COMPLETED && !objectiveSatisfied))
            throw new IllegalArgumentException("Quest state and objective completion disagree");
        boolean matches = switch (template.type()) {
            case RESOURCE_DELIVERY -> objective instanceof QuestObjective.Resource;
            case HOSTILE_PARTY_ELIMINATION -> objective instanceof QuestObjective.Party;
            case MEET_NPC -> objective instanceof QuestObjective.Meet;
            case RETURN_TO_SETTLEMENT -> objective instanceof QuestObjective.Return;
        };
        if (!matches) throw new IllegalArgumentException("Quest type and objective disagree");
        if (objective instanceof QuestObjective.Meet meet && meet.role() != QuestSourceRole.MAYOR)
            throw new IllegalArgumentException("First meeting requires the Mayor");
        if (objective instanceof QuestObjective.Party party) {
            PartyType expected = template == QuestTemplate.UNDEAD_REQUEST ? PartyType.UNDEAD_HORDE : PartyType.PILLAGER_PATROL;
            if (party.partyType() != expected) throw new IllegalArgumentException("Quest template and target party disagree");
        }
    }
    public QuestInstance withState(QuestState next) {
        Objects.requireNonNull(next, "next");
        if (state == next) return this;
        boolean allowed = switch (state) {
            case AVAILABLE -> next == QuestState.ACTIVE || next == QuestState.FAILED || next == QuestState.EXPIRED;
            case ACTIVE -> next == QuestState.COMPLETED || next == QuestState.FAILED || next == QuestState.EXPIRED;
            case COMPLETED, FAILED, EXPIRED -> false;
        };
        if (!allowed) throw new IllegalStateException("Invalid quest lifecycle transition " + state + " -> " + next);
        return new QuestInstance(id, template, source, objective, recommendedLevel, difficulty, rewards, next,
                objectiveSatisfied, createdAt, expiresAt);
    }
    public QuestInstance ready() {
        if (state != QuestState.ACTIVE) throw new IllegalStateException("Only active quests can become ready");
        return objectiveSatisfied ? this : new QuestInstance(id, template, source, objective, recommendedLevel,
                difficulty, rewards, state, true, createdAt, expiresAt);
    }
    public boolean expiresAtTick(long now) {
        if (now < 0) throw new IllegalArgumentException("Negative quest clock");
        return expiresAt >= 0 && now >= expiresAt;
    }
}

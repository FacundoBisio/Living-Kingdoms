package dev.livingkingdoms.quest.expansion.domain;

import java.util.Objects;

public final class QuestRewardPolicy {
    private QuestRewardPolicy() {}
    public static QuestRewards forTemplate(QuestTemplate template, QuestDifficulty difficulty, QuestRules rules) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(rules, "rules");
        if (template.category() == QuestCategory.MAIN) {
            // Iron's legacy delivery reward and the party's regional reputation already have their own receipts.
            return template == QuestTemplate.MAIN_RETURN
                    ? clamp(rules.mainReturnEmeralds(), rules.mainReturnReputation(), rules)
                    : new QuestRewards(0, 0);
        }
        int emeralds, reputation;
        switch (template.type()) {
            case RESOURCE_DELIVERY -> { emeralds = rules.resourceBaseEmeralds(); reputation = rules.resourceBaseReputation(); }
            case HOSTILE_PARTY_ELIMINATION -> { emeralds = rules.combatBaseEmeralds(); reputation = rules.combatBaseReputation(); }
            default -> throw new IllegalArgumentException("Unsupported dynamic quest reward handler");
        }
        int steps = difficulty.rewardSteps();
        return clamp((long) emeralds + (long) steps * rules.emeraldsPerDifficulty(),
                (long) reputation + (long) steps * rules.reputationPerDifficulty(), rules);
    }
    private static QuestRewards clamp(long emeralds, long reputation, QuestRules rules) {
        return new QuestRewards((int) Math.min(emeralds, rules.maximumEmeralds()),
                (int) Math.min(reputation, rules.maximumReputation()));
    }
}

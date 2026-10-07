package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.quest.domain.QuestTerms;

/** Terms snapshot when a board batch is generated; configuration edits cannot reroll accepted quests. */
public record QuestRules(int dynamicCount, int refreshTicks, int expirationTicks, int encounterRange,
        int requiredIron, int requiredWheat, int requiredLogs, int requiredStone,
        int resourceBaseEmeralds, int resourceBaseReputation, int combatBaseEmeralds, int combatBaseReputation,
        int mainReturnEmeralds, int mainReturnReputation, int emeraldsPerDifficulty, int reputationPerDifficulty,
        int maximumEmeralds, int maximumReputation) {
    public QuestRules {
        if (dynamicCount < 2 || dynamicCount > 4 || refreshTicks < 200 || refreshTicks > 1_728_000
                || expirationTicks < 200 || expirationTicks > 1_728_000 || encounterRange < 0 || encounterRange > 4096)
            throw new IllegalArgumentException("Invalid quest rotation rules");
        for (int quantity : new int[]{requiredIron, requiredWheat, requiredLogs, requiredStone})
            if (quantity < 1 || quantity > QuestTerms.MAX_ITEM_COUNT)
                throw new IllegalArgumentException("Invalid quest resource quantity");
        for (int emeralds : new int[]{resourceBaseEmeralds, combatBaseEmeralds, mainReturnEmeralds,
                emeraldsPerDifficulty, maximumEmeralds})
            if (emeralds < 0 || emeralds > QuestTerms.MAX_ITEM_COUNT)
                throw new IllegalArgumentException("Invalid emerald reward rule");
        for (int reputation : new int[]{resourceBaseReputation, combatBaseReputation, mainReturnReputation,
                reputationPerDifficulty, maximumReputation})
            if (reputation < 0 || reputation > QuestTerms.MAX_REPUTATION_REWARD)
                throw new IllegalArgumentException("Invalid reputation reward rule");
    }
    public static QuestRules defaults() {
        return new QuestRules(3, 48000, 48000, 256, 16, 32, 32, 32,
                4, 5, 4, 3, 6, 5, 1, 1, 32, 50);
    }
}

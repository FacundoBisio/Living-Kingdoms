package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.quest.domain.QuestTerms;

/** Zero rewards support introduction objectives without manufacturing a special payout handler. */
public record QuestRewards(int emeralds, int reputation) {
    public QuestRewards {
        if (emeralds < 0 || emeralds > QuestTerms.MAX_ITEM_COUNT
                || reputation < 0 || reputation > QuestTerms.MAX_REPUTATION_REWARD)
            throw new IllegalArgumentException("Quest rewards outside supported bounds");
    }
}

package dev.livingkingdoms.quest.domain;

/** Terms are copied from server configuration on acceptance and remain fixed afterward. */
public record QuestTerms(int requiredIron, int rewardEmeralds, int reputationReward) {
    public static final int MAX_ITEM_COUNT = 2_304;
    public static final int MAX_REPUTATION_REWARD = 1_000_000;

    public QuestTerms {
        if (requiredIron < 1 || requiredIron > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException("Required iron must be between 1 and " + MAX_ITEM_COUNT);
        }
        if (rewardEmeralds < 1 || rewardEmeralds > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException("Emerald reward must be between 1 and " + MAX_ITEM_COUNT);
        }
        if (reputationReward < 1 || reputationReward > MAX_REPUTATION_REWARD) {
            throw new IllegalArgumentException("Reputation reward must be between 1 and "
                    + MAX_REPUTATION_REWARD);
        }
    }
}

package dev.livingkingdoms.quest.domain;

import java.util.Objects;

/** Immutable view of one player's quest and their reputation with that settlement. */
public record PlayerSettlementProgress(QuestState state, QuestTerms terms, int reputation) {
    public PlayerSettlementProgress {
        Objects.requireNonNull(state, "state");
        if (state == QuestState.AVAILABLE) {
            if (terms != null) throw new IllegalArgumentException("Available quests cannot have accepted terms");
        } else {
            Objects.requireNonNull(terms, "Accepted quest terms");
        }
    }
}

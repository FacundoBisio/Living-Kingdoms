package dev.livingkingdoms.quest.expansion.domain;

import java.util.Objects;
import java.util.UUID;

public record QuestSource(UUID settlementId, UUID npcId, QuestSourceRole role) {
    public QuestSource {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(role, "role");
    }
}

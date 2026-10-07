package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.quest.domain.QuestTerms;
import java.util.Objects;

public record ResourceRequirement(ResourceKind resource, int count) {
    public ResourceRequirement {
        Objects.requireNonNull(resource, "resource");
        if (count < 1 || count > QuestTerms.MAX_ITEM_COUNT)
            throw new IllegalArgumentException("Resource requirement must be 1..2304");
    }
}

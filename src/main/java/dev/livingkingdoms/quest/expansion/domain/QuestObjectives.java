package dev.livingkingdoms.quest.expansion.domain;

import java.util.List;
import java.util.Objects;

public final class QuestObjectives {
    private QuestObjectives() {}
    public static QuestObjective.Resource forResourceTemplate(QuestTemplate template, QuestRules rules) {
        Objects.requireNonNull(rules, "rules");
        return switch (template) {
            case MAIN_IRON, IRON_REQUEST -> new QuestObjective.Resource(List.of(
                    new ResourceRequirement(ResourceKind.IRON_INGOT, rules.requiredIron())));
            case FOOD_REQUEST -> new QuestObjective.Resource(List.of(
                    new ResourceRequirement(ResourceKind.WHEAT, rules.requiredWheat())));
            case BUILDING_REQUEST -> new QuestObjective.Resource(List.of(
                    new ResourceRequirement(ResourceKind.LOGS, rules.requiredLogs()),
                    new ResourceRequirement(ResourceKind.STONE, rules.requiredStone())));
            default -> throw new IllegalArgumentException("Template has no resource objective: " + template);
        };
    }
}

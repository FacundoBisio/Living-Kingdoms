package dev.livingkingdoms.quest.expansion.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic chapter order, shared by board presentation and server acceptance validation. */
public final class QuestCatalog {
    private static final List<QuestTemplate> MAIN = List.of(QuestTemplate.FIRST_MEETING, QuestTemplate.MAIN_IRON,
            QuestTemplate.MAIN_PATROL, QuestTemplate.MAIN_RETURN);
    private QuestCatalog() {}
    public static List<QuestTemplate> mainChain() { return MAIN; }
    public static Optional<QuestTemplate> prerequisite(QuestTemplate template) {
        Objects.requireNonNull(template, "template");
        int index = MAIN.indexOf(template);
        return index > 0 ? Optional.of(MAIN.get(index - 1)) : Optional.empty();
    }
    public static boolean prerequisitesMet(QuestTemplate template, Set<QuestTemplate> completed) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(completed, "completed");
        int index = MAIN.indexOf(template);
        for (int i = 0; i < index; i++) if (!completed.contains(MAIN.get(i))) return false;
        return true;
    }
    public static Optional<QuestTemplate> nextMain(Set<QuestTemplate> completed) {
        Objects.requireNonNull(completed, "completed");
        return MAIN.stream().filter(template -> !completed.contains(template)).findFirst();
    }
}

package dev.livingkingdoms.rpg.domain;

import dev.livingkingdoms.quest.expansion.domain.QuestCategory;
import dev.livingkingdoms.quest.expansion.domain.QuestDifficulty;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import java.util.Objects;

/**
 * RPG reward layer granted on top of a quest's persisted emerald/reputation receipt. It is derived from
 * the template and difficulty, so no save schema stores it and claiming twice is impossible because the
 * underlying quest completion is already one-time.
 */
public record QuestBonus(int experience, int whetstones, int tomes) {
    public QuestBonus {
        if (experience < 0 || whetstones < 0 || tomes < 0 || experience > 5000 || whetstones > 16 || tomes > 4)
            throw new IllegalArgumentException("Quest bonus outside supported bounds");
    }

    public static final QuestBonus NONE = new QuestBonus(0, 0, 0);

    public boolean empty() { return experience == 0 && whetstones == 0 && tomes == 0; }

    public static QuestBonus of(QuestTemplate template, QuestDifficulty difficulty) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(difficulty, "difficulty");
        if (template.category() == QuestCategory.MAIN) {
            return switch (template) {
                case FIRST_MEETING -> NONE;
                case MAIN_RESIDENTS -> new QuestBonus(15, 0, 0);
                case MAIN_IRON -> new QuestBonus(30, 1, 0);
                case MAIN_DEVELOPMENT -> new QuestBonus(40, 1, 0);
                case MAIN_WATCHTOWER -> new QuestBonus(40, 1, 0);
                case MAIN_PATROL -> new QuestBonus(60, 2, 0);
                case MAIN_RETURN -> new QuestBonus(80, 2, 1);
                default -> NONE;
            };
        }
        int step = difficulty.rewardSteps();
        boolean combat = template.type() == dev.livingkingdoms.quest.domain.QuestType.HOSTILE_PARTY_ELIMINATION;
        int experience = 10 + step * 10 + (combat ? 10 : 0);
        // Whetstones arrive steadily from harder work; tomes only from the hardest requests.
        int whetstones = step >= 3 ? 2 : step >= 1 || combat ? 1 : 0;
        int tomes = step >= 4 ? 1 : 0;
        return new QuestBonus(experience, whetstones, tomes);
    }
}

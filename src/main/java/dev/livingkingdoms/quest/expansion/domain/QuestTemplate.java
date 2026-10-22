package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.quest.domain.QuestType;

/** Content templates share gameplay handlers and keep persisted identities independent of titles. */
public enum QuestTemplate {
    FIRST_MEETING("first_meeting", QuestCategory.MAIN, 1, QuestType.MEET_NPC, QuestSourceRole.MAYOR),
    MAIN_RESIDENTS("main_residents", QuestCategory.MAIN, 2, QuestType.MEET_NPC, QuestSourceRole.CITIZEN),
    MAIN_IRON("main_iron", QuestCategory.MAIN, 3, QuestType.RESOURCE_DELIVERY, QuestSourceRole.BLACKSMITH),
    MAIN_DEVELOPMENT("main_development", QuestCategory.MAIN, 4, QuestType.RETURN_TO_SETTLEMENT, QuestSourceRole.MAYOR),
    MAIN_WATCHTOWER("main_watchtower", QuestCategory.MAIN, 5, QuestType.RETURN_TO_SETTLEMENT, QuestSourceRole.GUARD_CAPTAIN),
    MAIN_PATROL("main_patrol", QuestCategory.MAIN, 6, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    MAIN_RETURN("main_return", QuestCategory.MAIN, 7, QuestType.RETURN_TO_SETTLEMENT, QuestSourceRole.MAYOR),
    IRON_REQUEST("iron_request", QuestCategory.DYNAMIC, 0, QuestType.RESOURCE_DELIVERY, QuestSourceRole.BLACKSMITH),
    FOOD_REQUEST("food_request", QuestCategory.DYNAMIC, 0, QuestType.RESOURCE_DELIVERY, QuestSourceRole.FARMER),
    BUILDING_REQUEST("building_request", QuestCategory.DYNAMIC, 0, QuestType.RESOURCE_DELIVERY, QuestSourceRole.CITIZEN),
    PILLAGER_REQUEST("pillager_request", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    UNDEAD_REQUEST("undead_request", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    LOCAL_DEFENSE("local_defense", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.BOARD),
    BANDIT_REQUEST("bandit_request", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    WARBAND_REQUEST("warband_request", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN);

    private final String id;
    private final QuestCategory category;
    private final int order;
    private final QuestType type;
    private final QuestSourceRole role;
    QuestTemplate(String id, QuestCategory category, int order, QuestType type, QuestSourceRole role) {
        this.id = id; this.category = category; this.order = order; this.type = type; this.role = role;
    }
    public String id() { return id; }
    public QuestCategory category() { return category; }
    public int order() { return order; }
    public int mainOrder() { return order; }
    public boolean introducedIn016() { return this == MAIN_RESIDENTS || this == MAIN_DEVELOPMENT || this == MAIN_WATCHTOWER; }
    public int stage() {
        return switch (this) {
            case FIRST_MEETING -> 1;
            case MAIN_RESIDENTS, MAIN_IRON -> 2;
            case MAIN_DEVELOPMENT -> 3;
            case MAIN_WATCHTOWER, MAIN_PATROL, MAIN_RETURN -> 4;
            default -> 0;
        };
    }
    public QuestType type() { return type; }
    public QuestSourceRole sourceRole() { return role; }
    public String chapter() { return category == QuestCategory.MAIN ? "first_steps" : ""; }
    public String titleKey() { return "quest.livingkingdoms." + id + ".title"; }
    public static QuestTemplate fromId(String id) {
        for (QuestTemplate template : values()) if (template.id.equals(id)) return template;
        throw new IllegalArgumentException("Unknown quest template: " + id);
    }
}

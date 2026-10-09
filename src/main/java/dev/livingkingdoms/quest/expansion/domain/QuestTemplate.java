package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.quest.domain.QuestType;

/** Content templates share gameplay handlers and keep persisted identities independent of titles. */
public enum QuestTemplate {
    FIRST_MEETING("first_meeting", QuestCategory.MAIN, 1, QuestType.MEET_NPC, QuestSourceRole.MAYOR),
    MAIN_IRON("main_iron", QuestCategory.MAIN, 2, QuestType.RESOURCE_DELIVERY, QuestSourceRole.BLACKSMITH),
    MAIN_PATROL("main_patrol", QuestCategory.MAIN, 3, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    MAIN_RETURN("main_return", QuestCategory.MAIN, 4, QuestType.RETURN_TO_SETTLEMENT, QuestSourceRole.MAYOR),
    IRON_REQUEST("iron_request", QuestCategory.DYNAMIC, 0, QuestType.RESOURCE_DELIVERY, QuestSourceRole.BLACKSMITH),
    FOOD_REQUEST("food_request", QuestCategory.DYNAMIC, 0, QuestType.RESOURCE_DELIVERY, QuestSourceRole.FARMER),
    BUILDING_REQUEST("building_request", QuestCategory.DYNAMIC, 0, QuestType.RESOURCE_DELIVERY, QuestSourceRole.CITIZEN),
    PILLAGER_REQUEST("pillager_request", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    UNDEAD_REQUEST("undead_request", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.GUARD_CAPTAIN),
    LOCAL_DEFENSE("local_defense", QuestCategory.DYNAMIC, 0, QuestType.HOSTILE_PARTY_ELIMINATION, QuestSourceRole.BOARD);

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
    public QuestType type() { return type; }
    public QuestSourceRole sourceRole() { return role; }
    public String chapter() { return category == QuestCategory.MAIN ? "first_steps" : ""; }
    public String titleKey() { return "quest.livingkingdoms." + id + ".title"; }
    public static QuestTemplate fromId(String id) {
        for (QuestTemplate template : values()) if (template.id.equals(id)) return template;
        throw new IllegalArgumentException("Unknown quest template: " + id);
    }
}

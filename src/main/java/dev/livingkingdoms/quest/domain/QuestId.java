package dev.livingkingdoms.quest.domain;

/** Stable save identifiers; localized quest titles are display values only. */
public enum QuestId {
    IRON_SHORTAGE("iron_shortage", QuestType.RESOURCE_DELIVERY);

    private final String id;
    private final QuestType type;

    QuestId(String id, QuestType type) {
        this.id = id;
        this.type = type;
    }

    public String id() {
        return id;
    }

    public QuestType type() {
        return type;
    }

    public static QuestId fromId(String id) {
        for (QuestId quest : values()) {
            if (quest.id.equals(id)) return quest;
        }
        throw new IllegalArgumentException("Unknown Living Kingdoms quest: " + id);
    }
}

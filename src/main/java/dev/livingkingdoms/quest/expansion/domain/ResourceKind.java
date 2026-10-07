package dev.livingkingdoms.quest.expansion.domain;

/** Stable content identifiers; Minecraft item/tag resolution belongs to the runtime. */
public enum ResourceKind {
    IRON_INGOT("iron_ingot"), WHEAT("wheat"), LOGS("logs"), STONE("stone");

    private final String id;
    ResourceKind(String id) { this.id = id; }
    public String id() { return id; }
    public static ResourceKind fromId(String id) {
        for (ResourceKind kind : values()) if (kind.id.equals(id)) return kind;
        throw new IllegalArgumentException("Unknown quest resource: " + id);
    }
}

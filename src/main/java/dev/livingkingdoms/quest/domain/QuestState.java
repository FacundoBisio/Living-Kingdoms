package dev.livingkingdoms.quest.domain;

/** The server-owned lifecycle of a player's quest. */
public enum QuestState {
    AVAILABLE,
    ACTIVE,
    COMPLETED,
    FAILED
}

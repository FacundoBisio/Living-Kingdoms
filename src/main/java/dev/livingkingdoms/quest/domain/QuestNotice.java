package dev.livingkingdoms.quest.domain;

import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import java.util.Objects;
import java.util.UUID;

/** Immutable server transition, never reconstructed from login snapshots. */
public record QuestNotice(UUID quest, Kind kind, QuestTemplate template, UUID settlement, int emeralds, int reputation) {
    public enum Kind { OBJECTIVE_COMPLETED, MISSION_COMPLETED, REWARD_CLAIMED }
    public record Transition(UUID player, QuestNotice notice) {}
    public record Key(UUID quest, Kind kind) {}
    public QuestNotice {
        Objects.requireNonNull(quest);Objects.requireNonNull(kind);Objects.requireNonNull(template);Objects.requireNonNull(settlement);
        if(emeralds<0 || emeralds>QuestTerms.MAX_ITEM_COUNT || reputation<0 || reputation>QuestTerms.MAX_REPUTATION_REWARD) throw new IllegalArgumentException("Invalid notice reward");
    }
    public Key key() {return new Key(quest,kind);}
}

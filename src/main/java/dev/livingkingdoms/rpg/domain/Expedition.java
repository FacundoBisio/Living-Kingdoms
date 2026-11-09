package dev.livingkingdoms.rpg.domain;

import java.util.Objects;
import java.util.UUID;

/** A resident away from the settlement. Timed in server game ticks, so offline time does not count. */
public record Expedition(UUID citizen, UUID settlement, UUID player, Kind kind, long startedAt, long endsAt) {
    public static final int MAX_PER_SETTLEMENT = 3;
    public enum Kind {
        FORAGE(6000, 0), MINE(12000, 0), EXPLORE(18000, 25);
        private final long duration; private final int rescuePercent;
        Kind(long duration, int rescuePercent) { this.duration = duration; this.rescuePercent = rescuePercent; }
        public long duration() { return duration; }
        /** Chance that an exploration brings back a survivor who asks to join the kingdom. */
        public int rescuePercent() { return rescuePercent; }
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public Expedition {
        Objects.requireNonNull(citizen); Objects.requireNonNull(settlement); Objects.requireNonNull(player); Objects.requireNonNull(kind);
        if (startedAt < 0 || endsAt <= startedAt) throw new IllegalArgumentException("Invalid expedition timing");
    }

    public static Expedition start(UUID citizen, UUID settlement, UUID player, Kind kind, long now) {
        return new Expedition(citizen, settlement, player, kind, now, Math.addExact(now, kind.duration()));
    }

    public boolean due(long now) { return now >= endsAt; }
    public long remaining(long now) { return Math.max(0, endsAt - now); }
}

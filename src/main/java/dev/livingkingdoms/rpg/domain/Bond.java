package dev.livingkingdoms.rpg.domain;

/**
 * Friendship with one citizen, shared by the kingdom. Talking once per calendar day and fulfilling the daily
 * personal request raise it; Loyal citizens give a small daily gift and unlock their profession perk; reaching
 * Devoted pays a one-time epic chest.
 */
public record Bond(int value, long lastTalkDay, long lastRequestDay, long lastGiftDay, boolean devotedRewarded) {
    public static final int MAX = 100, TALK = 3, REQUEST = 15, EXPEDITION = 5;
    public enum Level {
        STRANGER(0), FRIENDLY(25), TRUSTED(50), LOYAL(75), DEVOTED(100);
        private final int threshold;
        Level(int threshold) { this.threshold = threshold; }
        public int threshold() { return threshold; }
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
        public static Level of(int value) {
            Level result = STRANGER;
            for (var level : values()) if (value >= level.threshold) result = level;
            return result;
        }
    }

    public Bond {
        if (value < 0 || value > MAX) throw new IllegalArgumentException("Bond outside 0-100");
    }

    public static Bond empty() { return new Bond(0, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, false); }

    public Level level() { return Level.of(value); }
    public boolean loyal() { return value >= Level.LOYAL.threshold(); }
    public Bond add(int amount) { return new Bond(Math.max(0, Math.min(MAX, value + amount)), lastTalkDay, lastRequestDay, lastGiftDay, devotedRewarded); }

    public boolean canTalk(long day) { return day != lastTalkDay; }
    public Bond talked(long day) { return canTalk(day) ? new Bond(Math.min(MAX, value + TALK), day, lastRequestDay, lastGiftDay, devotedRewarded) : this; }

    public boolean canFulfill(long day) { return day != lastRequestDay; }
    public Bond fulfilled(long day) {
        if (!canFulfill(day)) throw new IllegalStateException("Request already fulfilled today");
        return new Bond(Math.min(MAX, value + REQUEST), lastTalkDay, day, lastGiftDay, devotedRewarded);
    }

    public boolean giftReady(long day) { return loyal() && day != lastGiftDay; }
    public Bond gifted(long day) { return new Bond(value, lastTalkDay, lastRequestDay, day, devotedRewarded); }

    public boolean devotedRewardReady() { return value >= MAX && !devotedRewarded; }
    public Bond devotedPaid() { return new Bond(value, lastTalkDay, lastRequestDay, lastGiftDay, true); }
}

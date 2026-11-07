package dev.livingkingdoms.rpg;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/** Calendar used by daily/weekly contracts: the server's local date, so resets happen at local midnight. */
public final class RpgClock {
    private static Supplier<LocalDateTime> now = () -> LocalDateTime.now(ZoneId.systemDefault());
    private RpgClock() {}
    public static long today() { return now.get().toLocalDate().toEpochDay(); }
    /** Weeks start on Monday (epoch day 0 was a Thursday). */
    public static long week() { return Math.floorDiv(today() + 3, 7); }
    public static long secondsUntilTomorrow() {
        var current = now.get();
        return java.time.Duration.between(current, current.toLocalDate().plusDays(1).atStartOfDay()).toSeconds();
    }
    /** Development/test override of the calendar; {@code null} restores the system clock. */
    public static void override(Supplier<LocalDateTime> clock) {
        now = clock == null ? () -> LocalDateTime.now(ZoneId.systemDefault()) : clock;
    }
}

package dev.livingkingdoms.rpg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Cheap, always-on timing of the RPG server-tick services (nanoTime around each call, fixed-size map). Read with
 * {@code /kingdom perf}: average and worst microseconds per tick since the last reset. No allocation per tick.
 */
public final class RpgPerf {
    private static final class Stat { long total, worst, calls; }
    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    private RpgPerf() {}

    public static <E> Consumer<E> timed(String name, Consumer<E> handler) {
        var stat = STATS.computeIfAbsent(name, k -> new Stat());
        return event -> {
            long start = System.nanoTime();
            try { handler.accept(event); } finally {
                long spent = System.nanoTime() - start;
                stat.total += spent; stat.calls++; if (spent > stat.worst) stat.worst = spent;
            }
        };
    }

    /** One line per service: name, average µs, worst µs, calls. */
    public static java.util.List<String> report() {
        var lines = new java.util.ArrayList<String>();
        STATS.forEach((name, s) -> lines.add(String.format(java.util.Locale.ROOT, "%s: avg %.1f µs, worst %.1f µs, %d ticks",
                name, s.calls == 0 ? 0 : s.total / 1000.0 / s.calls, s.worst / 1000.0, s.calls)));
        return lines;
    }
    public static void reset() { STATS.values().forEach(s -> { s.total = 0; s.worst = 0; s.calls = 0; }); }
}

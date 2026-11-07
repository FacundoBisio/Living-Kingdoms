package dev.livingkingdoms.rpg.domain;

import java.util.Objects;
import java.util.Set;

/** One contract objective. {@code target} is a resource id for DELIVER, empty otherwise. */
public record Contract(Kind kind, String target, int required, int progress, boolean claimed) {
    public enum Kind { SLAY, DELIVER, QUEST, SUPPLY, TRIAL }
    private static final Set<String> RESOURCES = Set.of("logs", "stone", "wheat", "iron_ingot");

    public Contract {
        Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(target, "target");
        if (required < 1 || required > 10_000 || progress < 0 || progress > required) throw new IllegalArgumentException("Invalid contract progress");
        if ((kind == Kind.DELIVER) != RESOURCES.contains(target) || kind != Kind.DELIVER && !target.isEmpty())
            throw new IllegalArgumentException("Invalid contract target");
    }

    public static Contract of(Kind kind, String target, int required) { return new Contract(kind, target, required, 0, false); }

    /** Deliveries are checked against the inventory at claim time; tracked kinds count up from events. */
    public boolean tracked() { return kind != Kind.DELIVER; }
    public boolean complete() { return !tracked() || progress >= required; }
    public Contract advance(int amount) {
        if (claimed || !tracked() || amount <= 0) return this;
        return new Contract(kind, target, required, Math.min(required, progress + amount), false);
    }
    public Contract claim() {
        if (claimed) throw new IllegalStateException("Contract already claimed");
        return new Contract(kind, target, required, tracked() ? required : progress, true);
    }
}

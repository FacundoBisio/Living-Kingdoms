package dev.livingkingdoms.rpg.domain;

/**
 * A kingdom wall ring. Tier 1 timber palisade, 2 cobblestone, 3 reinforced stone bricks. {@code built}
 * counts placed columns of the current ring; the ring is derived from terrain at build time.
 */
public record Fortification(int tier, int radius, int built, boolean complete) {
    public static final int MAX_TIER = 3, HEIGHT = 3, POST_SPACING = 12;
    public Fortification {
        if (tier < 0 || tier > MAX_TIER || radius < 8 || radius > 256 || built < 0) throw new IllegalArgumentException("Invalid fortification");
    }
    public static Fortification none(int radius) { return new Fortification(0, radius, 0, true); }

    /**
     * Wood, stone and iron to raise the next tier (ring of roughly 2πr columns). Amounts are capped below the
     * base Kingdom Storage capacity (256 per resource) so every tier is affordable without a Warehouse.
     */
    public static int[] cost(int nextTier, int radius) {
        int columns = (int) Math.ceil(2 * Math.PI * radius);
        return switch (nextTier) {
            case 1 -> new int[]{Math.clamp(columns / 3, 32, 192), 0, 0};
            case 2 -> new int[]{16, Math.clamp(columns / 2, 64, 224), 4};
            case 3 -> new int[]{16, Math.clamp(columns * 2 / 3, 96, 240), 16};
            default -> throw new IllegalArgumentException("No fortification tier " + nextTier);
        };
    }
    public Fortification upgrade() {
        if (tier >= MAX_TIER) throw new IllegalStateException("Walls are at the highest tier");
        return new Fortification(tier + 1, radius, 0, false);
    }
    public Fortification progress(int columns) { return new Fortification(tier, radius, built + Math.max(0, columns), complete); }
    public Fortification finished() { return new Fortification(tier, radius, built, true); }
    /** Progress shown to players: 100 once finished, otherwise built/total rounded down and clamped to 0..100. */
    public static int percent(int built, int total, boolean complete) {
        return complete ? 100 : total <= 0 ? 0 : Math.clamp(built * 100L / total, 0, 100);
    }
}

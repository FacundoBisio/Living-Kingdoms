package dev.livingkingdoms.npckingdom;

/**
 * Pure, deterministic rules for natural NPC kingdom placement. No world, entity or chunk access.
 * The world is divided into square region cells; each cell gets one seeded roll and at most one
 * candidate center, kept far enough from the cell edge that the territory never crosses it.
 */
public final class NpcKingdomPolicy {
    public static final int MIN_CELL_SIZE = 256;
    public static final int MAX_CELL_SIZE = 4096;
    public static final int MAX_RULER_LENGTH = 32;
    /** Extra clearance beyond the territory radius, so paths and plots stay well inside the cell. */
    public static final int EDGE_CLEARANCE = 32;
    private static final String[] RULERS = {
            "Aldric", "Maren", "Edwin", "Isolde", "Corwin", "Elsbeth", "Tobias", "Rowena",
            "Gareth", "Linnea", "Osric", "Brenna", "Leoric", "Wynne", "Harold", "Sabine",
            "Cedric", "Annora", "Bastian", "Elowen", "Dorian", "Mirela", "Fenwick", "Talia"};
    private static final String[] HOUSES = {
            "Ashford", "Brightwater", "Thornvale", "Greymoor", "Highcrest", "Oakheart",
            "Stonebrook", "Wolfden", "Redmere", "Silverfield", "Kestrel", "Hollowell"};

    private NpcKingdomPolicy() {}

    public record Cell(int x, int z) {
        public long key() { return ((long) x & 0xFFFFFFFFL) | ((long) z << 32); }
        public static Cell fromKey(long key) { return new Cell((int) key, (int) (key >> 32)); }
    }

    public record Center(int x, int z) {}

    public static Cell cell(int blockX, int blockZ, int cellSize) {
        requireCellSize(cellSize);
        return new Cell(Math.floorDiv(blockX, cellSize), Math.floorDiv(blockZ, cellSize));
    }

    /** Deterministic value in [0, 1) for one world seed and cell. */
    public static double roll(long seed, Cell cell) {
        return (mix(seed ^ cell.key() * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    public static boolean selected(long seed, Cell cell, double chance) {
        if (!(chance >= 0 && chance <= 1)) throw new IllegalArgumentException("Chance must be between 0 and 1");
        return roll(seed, cell) < chance;
    }

    /** Candidate center with at least {@code radius + EDGE_CLEARANCE} blocks to every cell edge. */
    public static Center center(long seed, Cell cell, int cellSize, int radius) {
        requireCellSize(cellSize);
        int margin = margin(radius);
        int span = cellSize - 2 * margin;
        if (span < 1) throw new IllegalArgumentException("Cell size is too small for the settlement radius");
        long bits = mix(seed + 0x632BE59BD9B4E019L ^ cell.key());
        int offsetX = Math.floorMod((int) bits, span);
        int offsetZ = Math.floorMod((int) (bits >>> 32), span);
        return new Center(Math.toIntExact((long) cell.x() * cellSize + margin + offsetX),
                Math.toIntExact((long) cell.z() * cellSize + margin + offsetZ));
    }

    public static int margin(int radius) {
        if (radius < 1) throw new IllegalArgumentException("Radius must be positive");
        return radius + EDGE_CLEARANCE;
    }

    /** Short, language-neutral ruler names from a fixed list; never longer than {@link #MAX_RULER_LENGTH}. */
    public static String ruler(long seed, Cell cell) {
        long bits = mix(seed ^ 0x5DEECE66DL ^ cell.key() * 0xC2B2AE3D27D4EB4FL);
        return RULERS[Math.floorMod((int) bits, RULERS.length)] + " " + HOUSES[Math.floorMod((int) (bits >>> 32), HOUSES.length)];
    }

    private static void requireCellSize(int cellSize) {
        if (cellSize < MIN_CELL_SIZE || cellSize > MAX_CELL_SIZE) throw new IllegalArgumentException("Invalid cell size: " + cellSize);
    }

    /** SplitMix64 finalizer. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}

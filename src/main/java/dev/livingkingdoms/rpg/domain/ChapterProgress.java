package dev.livingkingdoms.rpg.domain;

/**
 * A player's progress in the kingdom chapters: which mission is accepted (-1 none), which are claimed (bitmask
 * by mission index) and the hostile kills counted since the current mission was accepted.
 */
public record ChapterProgress(int accepted, long claimed, int kills) {
    public static final ChapterProgress EMPTY = new ChapterProgress(-1, 0, 0);
    public ChapterProgress {
        if (accepted < -1 || accepted > 62 || kills < 0) throw new IllegalArgumentException("Invalid chapter progress");
    }
    public boolean claimed(int index) { return (claimed & (1L << index)) != 0; }
    /** The first mission not yet claimed (missions unlock in order). */
    public int next(int total) { for (int i = 0; i < total; i++) if (!claimed(i)) return i; return total; }
    public ChapterProgress accept(int index) { return new ChapterProgress(index, claimed, 0); }
    public ChapterProgress kill() { return accepted < 0 ? this : new ChapterProgress(accepted, claimed, Math.min(1_000_000, kills + 1)); }
    public ChapterProgress claim(int index) { return new ChapterProgress(-1, claimed | (1L << index), 0); }
}

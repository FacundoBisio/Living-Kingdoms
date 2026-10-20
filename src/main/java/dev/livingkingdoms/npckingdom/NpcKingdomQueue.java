package dev.livingkingdoms.npckingdom;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.OptionalLong;

/** Transient, deduplicated FIFO of region cell keys. Bounded; overflow is dropped, never grown. */
public final class NpcKingdomQueue {
    public static final int DEFAULT_CAPACITY = 64;
    private final LinkedHashSet<Long> cells = new LinkedHashSet<>();
    private final int capacity;

    public NpcKingdomQueue() { this(DEFAULT_CAPACITY); }
    public NpcKingdomQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
    }

    /** False when the cell is already queued or the queue is full. */
    public boolean offer(long cell) {
        if (cells.contains(cell) || cells.size() >= capacity) return false;
        return cells.add(cell);
    }

    public OptionalLong poll() {
        Iterator<Long> iterator = cells.iterator();
        if (!iterator.hasNext()) return OptionalLong.empty();
        long cell = iterator.next();
        iterator.remove();
        return OptionalLong.of(cell);
    }

    public boolean contains(long cell) { return cells.contains(cell); }
    public int size() { return cells.size(); }
    public int capacity() { return capacity; }
    public void clear() { cells.clear(); }
}

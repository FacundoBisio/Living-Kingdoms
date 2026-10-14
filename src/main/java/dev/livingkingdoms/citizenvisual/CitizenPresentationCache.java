package dev.livingkingdoms.citizenvisual;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Client-thread cache with explicit capacity, age and identity checks (network IDs may be reused). */
public final class CitizenPresentationCache {
    public static final int MAX_CAPACITY = 1024;
    public record Entry(CitizenPresentation data, long receivedAt, long actionAt) {}
    private final int capacity;
    private final long lifetime;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();

    public CitizenPresentationCache(int capacity, long lifetime) {
        if (capacity < 1 || capacity > MAX_CAPACITY || lifetime < 1) throw new IllegalArgumentException("Invalid cache bounds");
        this.capacity = capacity; this.lifetime = lifetime;
    }
    public void put(CitizenPresentation data, long tick, boolean action) {
        Entry old = entries.remove(data.entityUuid());
        entries.put(data.entityUuid(), new Entry(data, tick, action ? tick : old == null ? -1000 : old.actionAt));
        if (entries.size() > capacity) entries.pollFirstEntry();
    }
    public Entry get(int entityId, UUID uuid, long tick) {
        Entry entry = entries.get(uuid);
        if (entry == null) return null;
        if (tick < entry.receivedAt || tick - entry.receivedAt > lifetime || entityId != entry.data.entityId()) {
            entries.remove(uuid); return null;
        }
        return entry;
    }
    public void remove(UUID uuid) { entries.remove(uuid); }
    public void clear() { entries.clear(); }
    public int size() { return entries.size(); }
}

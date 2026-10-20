package dev.livingkingdoms.npckingdom.persistence;

import dev.livingkingdoms.npckingdom.NpcKingdomPolicy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Independent NPC kingdom registry plus bounded natural-generation bookkeeping. One per world, held by
 * the Overworld data storage; access on the server thread. Settlement records themselves stay in
 * {@code SettlementSavedData} (schema unchanged); this store only marks which ones are NPC-ruled.
 */
public final class NpcKingdomSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_npc_kingdoms";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_KINGDOMS = 4096;
    public static final int MAX_ATTEMPTED_CELLS = 16384;
    public static final int MAX_UNLOADED_RETRIES = 3;
    private final Map<UUID, Kingdom> kingdoms = new LinkedHashMap<>();
    /** Insertion ordered; the eldest cell is evicted at the cap, so a very old cell may be retried once. */
    private final LinkedHashSet<Long> attempted = new LinkedHashSet<>();
    private final LinkedHashMap<Long, Integer> retries = new LinkedHashMap<>();
    private long nextAttemptAt;

    public record Kingdom(String ruler, long createdAt) {
        public Kingdom {
            Objects.requireNonNull(ruler, "ruler");
            if (ruler.isBlank() || ruler.length() > NpcKingdomPolicy.MAX_RULER_LENGTH
                    || ruler.chars().anyMatch(c -> Character.isISOControl(c) || c == '§'))
                throw new IllegalArgumentException("Invalid NPC kingdom ruler name");
            if (createdAt < 0) throw new IllegalArgumentException("Invalid NPC kingdom creation time");
        }
    }

    public static NpcKingdomSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("NPC kingdom data must be accessed on the server thread");
        return getOrCreate(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    public static NpcKingdomSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        // Vanilla may catch read/deserialize failures and return null. Never treat an unreadable
        // existing save as a new world, because it could then be overwritten.
        Factory<NpcKingdomSavedData> factory = new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat")))
                throw new IllegalStateException("Existing Living Kingdoms NPC kingdom data could not be loaded; "
                        + "refusing to overwrite it. Back up the world and inspect the server log.");
            return new NpcKingdomSavedData();
        }, NpcKingdomSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    public boolean isNpc(UUID settlement) { return kingdoms.containsKey(Objects.requireNonNull(settlement, "settlement")); }
    public Optional<String> ruler(UUID settlement) { return Optional.ofNullable(kingdoms.get(settlement)).map(Kingdom::ruler); }
    public Optional<Kingdom> kingdom(UUID settlement) { return Optional.ofNullable(kingdoms.get(settlement)); }
    public int count() { return kingdoms.size(); }
    public List<UUID> ids() { return List.copyOf(kingdoms.keySet()); }

    /** False for duplicates or when the registry is full; never replaces an existing ruler. */
    public boolean register(UUID settlement, String ruler, long now) {
        Objects.requireNonNull(settlement, "settlement");
        Kingdom kingdom = new Kingdom(ruler, now);
        if (kingdoms.containsKey(settlement) || kingdoms.size() >= MAX_KINGDOMS) return false;
        kingdoms.put(settlement, kingdom);
        setDirty();
        return true;
    }

    public boolean attempted(long cell) { return attempted.contains(cell); }
    public int attemptedCount() { return attempted.size(); }
    public int retries(long cell) { return retries.getOrDefault(cell, 0); }

    public void markAttempted(long cell) {
        retries.remove(cell);
        if (attempted.remove(cell)) { attempted.add(cell); setDirty(); return; }
        if (attempted.size() >= MAX_ATTEMPTED_CELLS) {
            Iterator<Long> eldest = attempted.iterator();
            eldest.next();
            eldest.remove();
        }
        attempted.add(cell);
        setDirty();
    }

    /**
     * Records one UNLOADED_CHUNKS outcome. Returns true while the cell may be retried; after
     * {@link #MAX_UNLOADED_RETRIES} outcomes the cell becomes attempted and false is returned.
     */
    public boolean retryUnloaded(long cell) {
        if (attempted.contains(cell)) return false;
        int count = retries.getOrDefault(cell, 0) + 1;
        if (count >= MAX_UNLOADED_RETRIES) { markAttempted(cell); return false; }
        if (!retries.containsKey(cell) && retries.size() >= MAX_ATTEMPTED_CELLS) {
            Iterator<Long> eldest = retries.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        retries.put(cell, count);
        setDirty();
        return true;
    }

    public long nextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(long gameTime) {
        if (gameTime < 0) throw new IllegalArgumentException("Invalid next attempt time");
        if (nextAttemptAt == gameTime) return;
        nextAttemptAt = gameTime;
        setDirty();
    }

    public static NpcKingdomSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema_version", Tag.TAG_INT)) throw new IllegalArgumentException("Missing NPC kingdom schema");
        int schema = tag.getInt("schema_version");
        if (schema < 1 || schema > SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported NPC kingdom schema: " + schema);
        require(tag, "kingdoms", Tag.TAG_LIST);
        require(tag, "attempted_cells", Tag.TAG_LONG_ARRAY);
        require(tag, "retries", Tag.TAG_LIST);
        require(tag, "next_attempt_at", Tag.TAG_LONG);
        NpcKingdomSavedData data = new NpcKingdomSavedData();
        ListTag entries = (ListTag) tag.get("kingdoms");
        if (entries.size() > MAX_KINGDOMS || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid NPC kingdom list");
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("settlement")) throw new IllegalArgumentException("Invalid NPC kingdom settlement");
            require(entry, "ruler", Tag.TAG_STRING);
            require(entry, "created", Tag.TAG_LONG);
            Kingdom kingdom = new Kingdom(entry.getString("ruler"), entry.getLong("created"));
            if (data.kingdoms.putIfAbsent(entry.getUUID("settlement"), kingdom) != null)
                throw new IllegalArgumentException("Duplicate NPC kingdom");
        }
        long[] cells = tag.getLongArray("attempted_cells");
        if (cells.length > MAX_ATTEMPTED_CELLS) throw new IllegalArgumentException("Too many attempted NPC kingdom cells");
        for (long cell : cells) if (!data.attempted.add(cell)) throw new IllegalArgumentException("Duplicate attempted NPC kingdom cell");
        ListTag retryEntries = (ListTag) tag.get("retries");
        if (retryEntries.size() > MAX_ATTEMPTED_CELLS || !retryEntries.isEmpty() && retryEntries.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid NPC kingdom retry list");
        for (int i = 0; i < retryEntries.size(); i++) {
            CompoundTag entry = retryEntries.getCompound(i);
            require(entry, "cell", Tag.TAG_LONG);
            require(entry, "count", Tag.TAG_INT);
            long cell = entry.getLong("cell");
            int count = entry.getInt("count");
            if (count < 1 || count >= MAX_UNLOADED_RETRIES || data.attempted.contains(cell)
                    || data.retries.putIfAbsent(cell, count) != null) throw new IllegalArgumentException("Invalid NPC kingdom retry");
        }
        long next = tag.getLong("next_attempt_at");
        if (next < 0) throw new IllegalArgumentException("Invalid NPC kingdom cooldown");
        data.nextAttemptAt = next;
        data.setDirty(schema < SCHEMA_VERSION);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag entries = new ListTag();
        kingdoms.forEach((id, kingdom) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("settlement", id);
            entry.putString("ruler", kingdom.ruler());
            entry.putLong("created", kingdom.createdAt());
            entries.add(entry);
        });
        tag.put("kingdoms", entries);
        tag.put("attempted_cells", new LongArrayTag(attempted.stream().mapToLong(Long::longValue).toArray()));
        ListTag retryEntries = new ListTag();
        retries.forEach((cell, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("cell", cell);
            entry.putInt("count", count);
            retryEntries.add(entry);
        });
        tag.put("retries", retryEntries);
        tag.putLong("next_attempt_at", nextAttemptAt);
        return tag;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or invalid NPC kingdom field: " + key);
    }
}

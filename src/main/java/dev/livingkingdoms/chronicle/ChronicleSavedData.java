package dev.livingkingdoms.chronicle;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.*;
import java.nio.file.*;
import java.util.*;

/** Separate v0.18 store: never rewrites settlement, citizen, profession or v0.17 schemas. */
public final class ChronicleSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_chronicle";
    public static final int HISTORY_LIMIT = 128, RECEIPT_LIMIT = 512, KINGDOM_LIMIT = 1024;
    private static final class Ledger {
        final ArrayDeque<ChronicleEvent> history = new ArrayDeque<>();
        final LinkedHashSet<String> receipts = new LinkedHashSet<>();
    }
    private final Map<UUID, Ledger> kingdoms = new LinkedHashMap<>();
    private Thread owner;
    private java.util.function.Consumer<ChronicleEvent> transitionSink;
    public static ChronicleSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Chronicle requires server authority");
        var data = getOrCreate(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
        data.owner = Thread.currentThread();
        if (data.transitionSink == null) data.observeTransitions(event -> ChronicleService.send(server, event));
        return data;
    }
    public static ChronicleSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        return storage.computeIfAbsent(new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat")))
                throw new IllegalStateException("Unreadable existing kingdom chronicle; refusing to overwrite it");
            return new ChronicleSavedData();
        }, ChronicleSavedData::load), DATA_NAME);
    }
    private void authority() {
        if (owner != null && owner != Thread.currentThread()) throw new IllegalStateException("Chronicle requires server authority");
    }
    /** Subscribers observe newly committed facts only; history reads and deserialization emit nothing. */
    public void observeTransitions(java.util.function.Consumer<ChronicleEvent> sink) { authority(); transitionSink = Objects.requireNonNull(sink); }
    public boolean record(ChronicleEvent event) {
        authority(); Objects.requireNonNull(event);
        var ledger = kingdoms.get(event.settlement());
        if (ledger == null) {
            if (kingdoms.size() >= KINGDOM_LIMIT) return false;
            ledger = new Ledger(); kingdoms.put(event.settlement(), ledger);
        }
        String key = receiptKey(event);
        if (!ledger.receipts.add(key)) return false;
        while (ledger.receipts.size() > RECEIPT_LIMIT) ledger.receipts.remove(ledger.receipts.iterator().next());
        if (ledger.history.size() == HISTORY_LIMIT) ledger.history.removeFirst();
        ledger.history.addLast(event); setDirty();
        if (transitionSink != null) transitionSink.accept(event);
        return true;
    }
    public List<ChronicleEvent> history(UUID settlement) {
        authority(); var ledger = kingdoms.get(Objects.requireNonNull(settlement));
        return ledger == null ? List.of() : List.copyOf(ledger.history);
    }
    static String receiptKey(ChronicleEvent event) {
        String family = event.kind() == ChronicleEvent.Kind.CITIZEN_DIED || event.kind() == ChronicleEvent.Kind.GUARD_DIED
                ? "death" : event.kind().key();
        return family + ":" + event.receipt();
    }
    static CompoundTag eventTag(ChronicleEvent event) {
        var tag = new CompoundTag(); tag.putUUID("event", event.id()); tag.putString("kind", event.kind().key());
        tag.putLong("tick", event.tick()); tag.putString("receipt", event.receipt()); tag.putString("subject", event.subject()); return tag;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        authority(); tag.putInt("schema_version", 1); var entries = new ListTag();
        kingdoms.forEach((id, ledger) -> {
            var entry = new CompoundTag(); entry.putUUID("settlement", id); var history = new ListTag();
            ledger.history.forEach(event -> history.add(eventTag(event))); entry.put("history", history);
            var receipts = new ListTag(); ledger.receipts.forEach(receipt -> receipts.add(StringTag.valueOf(receipt)));
            entry.put("receipts", receipts); entries.add(entry);
        });
        tag.put("kingdoms", entries); return tag;
    }
    public static ChronicleSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema_version", Tag.TAG_INT) || tag.getInt("schema_version") != 1)
            throw new IllegalArgumentException("Unsupported chronicle schema");
        var data = new ChronicleSavedData(); var entries = list(tag, "kingdoms", Tag.TAG_COMPOUND, KINGDOM_LIMIT);
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i); UUID settlement = uuid(entry, "settlement"); var ledger = new Ledger();
            if (data.kingdoms.putIfAbsent(settlement, ledger) != null) throw new IllegalArgumentException("Duplicate chronicle kingdom");
            var receipts = list(entry, "receipts", Tag.TAG_STRING, RECEIPT_LIMIT);
            for (int j = 0; j < receipts.size(); j++) {
                String key = receipts.getString(j);
                if (key.isBlank() || key.length() > 200 || key.codePoints().anyMatch(Character::isISOControl)
                        || !ledger.receipts.add(key)) throw new IllegalArgumentException("Invalid chronicle receipt");
            }
            var history = list(entry, "history", Tag.TAG_COMPOUND, HISTORY_LIMIT); var ids = new HashSet<UUID>(); var keys = new HashSet<String>();
            for (int j = 0; j < history.size(); j++) {
                var e = history.getCompound(j);
                if (!e.contains("kind", Tag.TAG_STRING) || !e.contains("tick", Tag.TAG_LONG)
                        || !e.contains("receipt", Tag.TAG_STRING) || !e.contains("subject", Tag.TAG_STRING))
                    throw new IllegalArgumentException("Malformed chronicle event");
                var event = new ChronicleEvent(uuid(e, "event"), settlement, ChronicleEvent.Kind.valueOf(e.getString("kind").toUpperCase(Locale.ROOT)),
                        e.getLong("tick"), e.getString("receipt"), e.getString("subject"));
                if (!ids.add(event.id()) || !keys.add(receiptKey(event)) || !ledger.receipts.contains(receiptKey(event)))
                    throw new IllegalArgumentException("Inconsistent chronicle history");
                ledger.history.addLast(event);
            }
        }
        return data;
    }
    private static UUID uuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) throw new IllegalArgumentException("Invalid chronicle UUID: " + field); return tag.getUUID(field);
    }
    private static ListTag list(CompoundTag tag, String field, int type, int limit) {
        if (!tag.contains(field, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing chronicle list: " + field);
        var list = (ListTag) tag.get(field);
        if (list.size() > limit || !list.isEmpty() && list.getElementType() != type)
            throw new IllegalArgumentException("Invalid chronicle list: " + field);
        return list;
    }
}

package dev.livingkingdoms.rpg.persistence;

import dev.livingkingdoms.rpg.domain.Contract;
import dev.livingkingdoms.rpg.domain.ContractBoard;
import dev.livingkingdoms.rpg.domain.KingdomRank;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Kingdom rank XP per settlement and each player's contract board. New file in 0.22
 * ({@code <world>/data/livingkingdoms_rpg.dat}); older mod versions ignore it. Server thread only, capped.
 * Schema 2 (0.24) adds citizen bonds, active expeditions and undelivered expedition loot; schema 1 files load
 * with those empty and are rewritten as schema 2. Schema 3 (0.30) adds applied town decoration tiers, learned
 * player techniques and kingdom walls; older files load with those empty. 0.38 adds an optional {@code oldRadius} int
 * to a wall compound (old ring still being demolished) without a schema bump: older files lack it, older mods ignore it.
 */
public final class RpgSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_rpg";
    public static final int SCHEMA_VERSION = 3;
    public static final int MAX_KINGDOMS = 65536, MAX_PLAYERS = 65536, MAX_BONDS = 262144, MAX_EXPEDITIONS = 4096, MAX_PENDING_STACKS = 64;
    private final Map<UUID, Long> kingdomXp = new LinkedHashMap<>();
    private final Map<UUID, ContractBoard> boards = new LinkedHashMap<>();
    private final Map<UUID, dev.livingkingdoms.rpg.domain.Bond> bonds = new LinkedHashMap<>();
    private final Map<UUID, dev.livingkingdoms.rpg.domain.Expedition> expeditions = new LinkedHashMap<>();
    /** Expedition loot for players who were offline at return time, as saved item stacks. */
    private final Map<UUID, java.util.List<CompoundTag>> pending = new LinkedHashMap<>();
    private final Map<UUID, Integer> decor = new LinkedHashMap<>();
    private final Map<UUID, java.util.Set<dev.livingkingdoms.rpg.domain.Technique>> techniques = new LinkedHashMap<>();
    private final Map<UUID, dev.livingkingdoms.rpg.domain.Fortification> walls = new LinkedHashMap<>();
    private final Map<UUID, dev.livingkingdoms.rpg.domain.WallDesign> designs = new LinkedHashMap<>();
    /** Old wall radius still being demolished after a move (keys are a subset of {@link #walls}). */
    private final Map<UUID, Integer> relocations = new LinkedHashMap<>();
    private final java.util.Set<UUID> lodges = new java.util.LinkedHashSet<>();
    private final Map<UUID, dev.livingkingdoms.rpg.domain.ChapterProgress> chapters = new LinkedHashMap<>();

    public int decor(UUID settlement) { return decor.getOrDefault(settlement, 0); }
    public void markDecor(UUID settlement, int bits) {
        int next = decor(settlement) | bits;
        if (next == decor(settlement) || !decor.containsKey(settlement) && decor.size() >= MAX_KINGDOMS) return;
        decor.put(settlement, next); setDirty();
    }
    public java.util.Set<dev.livingkingdoms.rpg.domain.Technique> techniques(UUID player) {
        return java.util.Set.copyOf(techniques.getOrDefault(player, java.util.Set.of()));
    }
    public boolean learn(UUID player, dev.livingkingdoms.rpg.domain.Technique technique) {
        if (!techniques.containsKey(player) && techniques.size() >= MAX_PLAYERS) return false;
        var set = techniques.computeIfAbsent(player, ignored -> java.util.EnumSet.noneOf(dev.livingkingdoms.rpg.domain.Technique.class));
        if (!set.add(technique)) return false;
        setDirty(); return true;
    }
    public java.util.Optional<dev.livingkingdoms.rpg.domain.Fortification> wall(UUID settlement) { return java.util.Optional.ofNullable(walls.get(settlement)); }
    public dev.livingkingdoms.rpg.domain.WallDesign design(UUID settlement) { return designs.getOrDefault(settlement, dev.livingkingdoms.rpg.domain.WallDesign.DEFAULT); }
    public void putDesign(UUID settlement, dev.livingkingdoms.rpg.domain.WallDesign design) {
        if (design.equals(designs.get(settlement))) return;
        if (!designs.containsKey(settlement) && designs.size() >= MAX_KINGDOMS) return;
        designs.put(settlement, design); setDirty();
    }
    public boolean lodge(UUID settlement) { return lodges.contains(settlement); }
    public void markLodge(UUID settlement) { if (lodges.size() < MAX_KINGDOMS && lodges.add(settlement)) setDirty(); }
    public dev.livingkingdoms.rpg.domain.ChapterProgress chapter(UUID player) { return chapters.getOrDefault(player, dev.livingkingdoms.rpg.domain.ChapterProgress.EMPTY); }
    public void putChapter(UUID player, dev.livingkingdoms.rpg.domain.ChapterProgress progress) {
        if (progress.equals(chapters.get(player))) return;
        if (!chapters.containsKey(player) && chapters.size() >= MAX_KINGDOMS * 8) return;
        chapters.put(player, progress); setDirty();
    }
    public java.util.List<UUID> walledSettlements() { return java.util.List.copyOf(walls.keySet()); }
    /**
     * Moving a standing wall (tier > 0) to a new radius records the old radius for demolition, unless one is already
     * pending (the first old ring is the one still standing). Every path that puts a tier gets this, admin ones included.
     */
    public boolean putWall(UUID settlement, dev.livingkingdoms.rpg.domain.Fortification wall) {
        if (!walls.containsKey(settlement) && walls.size() >= MAX_KINGDOMS) return false;
        var previous = walls.get(settlement);
        if (wall.equals(previous)) return true;
        if (previous != null && previous.tier() > 0 && previous.radius() != wall.radius()) relocations.putIfAbsent(settlement, previous.radius());
        walls.put(settlement, wall); setDirty(); return true;
    }
    /** Radius of an old ring still being demolished after the wall moved, if any. */
    public java.util.Optional<Integer> oldRadius(UUID settlement) { return java.util.Optional.ofNullable(relocations.get(settlement)); }
    public java.util.List<UUID> relocating() { return java.util.List.copyOf(relocations.keySet()); }
    public void putOldRadius(UUID settlement, int radius) {
        if (!walls.containsKey(settlement) || Integer.valueOf(radius).equals(relocations.get(settlement))) return;
        relocations.put(settlement, radius); setDirty();
    }
    public void clearOldRadius(UUID settlement) { if (relocations.remove(settlement) != null) setDirty(); }

    public dev.livingkingdoms.rpg.domain.Bond bond(UUID citizen) { return bonds.getOrDefault(Objects.requireNonNull(citizen), dev.livingkingdoms.rpg.domain.Bond.empty()); }
    public boolean putBond(UUID citizen, dev.livingkingdoms.rpg.domain.Bond bond) {
        Objects.requireNonNull(citizen); Objects.requireNonNull(bond);
        if (!bonds.containsKey(citizen) && bonds.size() >= MAX_BONDS) return false;
        if (bond.equals(bonds.get(citizen))) return true;
        bonds.put(citizen, bond); setDirty(); return true;
    }

    public java.util.Optional<dev.livingkingdoms.rpg.domain.Expedition> expedition(UUID citizen) { return java.util.Optional.ofNullable(expeditions.get(citizen)); }
    public java.util.List<dev.livingkingdoms.rpg.domain.Expedition> expeditions() { return java.util.List.copyOf(expeditions.values()); }
    public long expeditionsIn(UUID settlement) { return expeditions.values().stream().filter(e -> e.settlement().equals(settlement)).count(); }
    public boolean startExpedition(dev.livingkingdoms.rpg.domain.Expedition expedition) {
        if (expeditions.containsKey(expedition.citizen()) || expeditions.size() >= MAX_EXPEDITIONS
                || expeditionsIn(expedition.settlement()) >= dev.livingkingdoms.rpg.domain.Expedition.MAX_PER_SETTLEMENT) return false;
        expeditions.put(expedition.citizen(), expedition); setDirty(); return true;
    }
    public boolean endExpedition(UUID citizen) {
        if (expeditions.remove(citizen) == null) return false;
        setDirty(); return true;
    }

    public java.util.List<CompoundTag> pending(UUID player) { return java.util.List.copyOf(pending.getOrDefault(player, java.util.List.of())); }
    /** Stores up to {@link #MAX_PENDING_STACKS} stacks per player; extra stacks are reported as not stored. */
    public int addPending(UUID player, java.util.List<CompoundTag> stacks) {
        if (!pending.containsKey(player) && pending.size() >= MAX_PLAYERS) return 0;
        var list = pending.computeIfAbsent(player, ignored -> new java.util.ArrayList<>());
        int stored = 0;
        for (var stack : stacks) { if (list.size() >= MAX_PENDING_STACKS) break; list.add(stack.copy()); stored++; }
        if (list.isEmpty()) pending.remove(player);
        if (stored > 0) setDirty();
        return stored;
    }
    public java.util.List<CompoundTag> takePending(UUID player) {
        var list = pending.remove(player);
        if (list == null) return java.util.List.of();
        setDirty(); return list;
    }

    public static RpgSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("RPG data must be accessed on the server thread");
        return getOrCreate(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    public static RpgSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        Factory<RpgSavedData> factory = new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat")))
                throw new IllegalStateException("Existing Living Kingdoms RPG data could not be loaded; "
                        + "refusing to overwrite it. Back up the world and inspect the server log.");
            return new RpgSavedData();
        }, RpgSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    public long kingdomXp(UUID settlement) { return kingdomXp.getOrDefault(Objects.requireNonNull(settlement), 0L); }

    /** Returns the new total; refuses new kingdoms beyond the cap instead of evicting real progress. */
    public long addKingdomXp(UUID settlement, long amount) {
        Objects.requireNonNull(settlement, "settlement");
        if (amount <= 0) return kingdomXp(settlement);
        if (!kingdomXp.containsKey(settlement) && kingdomXp.size() >= MAX_KINGDOMS) return 0;
        long next = Math.min(KingdomRank.MAX_XP, kingdomXp(settlement) + amount);
        kingdomXp.put(settlement, next); setDirty();
        return next;
    }

    public ContractBoard board(UUID player) { return boards.getOrDefault(Objects.requireNonNull(player), ContractBoard.empty()); }

    public boolean putBoard(UUID player, ContractBoard board) {
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(board, "board");
        if (!boards.containsKey(player) && boards.size() >= MAX_PLAYERS) return false;
        if (board.equals(boards.get(player))) return true;
        boards.put(player, board); setDirty();
        return true;
    }

    public static RpgSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema_version", Tag.TAG_INT)) throw new IllegalArgumentException("Missing RPG schema");
        int schema = tag.getInt("schema_version");
        if (schema < 1 || schema > SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported RPG schema: " + schema);
        var data = new RpgSavedData();
        for (var raw : list(tag, "kingdoms")) {
            var entry = (CompoundTag) raw;
            if (!entry.hasUUID("settlement") || !entry.contains("xp", Tag.TAG_LONG)) throw new IllegalArgumentException("Invalid kingdom rank");
            long xp = entry.getLong("xp");
            if (xp < 0 || xp > KingdomRank.MAX_XP || data.kingdomXp.put(entry.getUUID("settlement"), xp) != null)
                throw new IllegalArgumentException("Invalid or duplicate kingdom rank");
        }
        for (var raw : list(tag, "boards")) {
            var entry = (CompoundTag) raw;
            if (!entry.hasUUID("player")) throw new IllegalArgumentException("Invalid contract board player");
            var daily = new ArrayList<Contract>();
            for (var c : entry.getList("daily", Tag.TAG_COMPOUND)) daily.add(contract((CompoundTag) c));
            var board = new ContractBoard(entry.getLong("day"), daily, entry.getLong("week"),
                    entry.contains("weekly", Tag.TAG_COMPOUND) ? contract(entry.getCompound("weekly")) : null,
                    entry.getInt("streak"), entry.getLong("last_full_day"), entry.getBoolean("bonus_claimed"));
            if (data.boards.put(entry.getUUID("player"), board) != null) throw new IllegalArgumentException("Duplicate contract board");
        }
        if (schema >= 2) {
            for (var raw : list(tag, "bonds")) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("citizen")) throw new IllegalArgumentException("Invalid bond citizen");
                var bond = new dev.livingkingdoms.rpg.domain.Bond(e.getInt("value"), e.getLong("talk"), e.getLong("request"), e.getLong("gift"), e.getBoolean("devoted"));
                if (data.bonds.put(e.getUUID("citizen"), bond) != null) throw new IllegalArgumentException("Duplicate bond");
            }
            for (var raw : list(tag, "expeditions")) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("citizen") || !e.hasUUID("settlement") || !e.hasUUID("player")) throw new IllegalArgumentException("Invalid expedition");
                var expedition = new dev.livingkingdoms.rpg.domain.Expedition(e.getUUID("citizen"), e.getUUID("settlement"), e.getUUID("player"),
                        dev.livingkingdoms.rpg.domain.Expedition.Kind.valueOf(e.getString("kind")), e.getLong("started"), e.getLong("ends"));
                if (data.expeditions.put(expedition.citizen(), expedition) != null) throw new IllegalArgumentException("Duplicate expedition");
            }
            for (var raw : list(tag, "pending")) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("player")) throw new IllegalArgumentException("Invalid pending loot owner");
                var stacks = new java.util.ArrayList<CompoundTag>();
                for (var stack : e.getList("items", Tag.TAG_COMPOUND)) stacks.add((CompoundTag) stack);
                if (stacks.size() > MAX_PENDING_STACKS || data.pending.put(e.getUUID("player"), stacks) != null) throw new IllegalArgumentException("Invalid pending loot");
            }
        }
        if (schema >= 3) {
            for (var raw : list(tag, "decor")) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("settlement") || data.decor.put(e.getUUID("settlement"), e.getInt("bits")) != null) throw new IllegalArgumentException("Invalid decor");
            }
            for (var raw : list(tag, "techniques")) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("player")) throw new IllegalArgumentException("Invalid technique owner");
                var set = java.util.EnumSet.noneOf(dev.livingkingdoms.rpg.domain.Technique.class);
                for (var id : e.getList("known", Tag.TAG_STRING)) set.add(dev.livingkingdoms.rpg.domain.Technique.fromId(id.getAsString()));
                if (data.techniques.put(e.getUUID("player"), set) != null) throw new IllegalArgumentException("Duplicate techniques");
            }
            for (var raw : list(tag, "walls")) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("settlement")) throw new IllegalArgumentException("Invalid wall");
                var wall = new dev.livingkingdoms.rpg.domain.Fortification(e.getInt("tier"), e.getInt("radius"), e.getInt("built"), e.getBoolean("complete"));
                if (data.walls.put(e.getUUID("settlement"), wall) != null) throw new IllegalArgumentException("Duplicate wall");
                // 0.36 layout fields are optional: older files keep the default two gates, no towers.
                if (e.contains("gates")) data.designs.put(e.getUUID("settlement"), new dev.livingkingdoms.rpg.domain.WallDesign(e.getInt("gates"), e.getBoolean("towers"), e.getBoolean("gatehouses")));
                // 0.38 optional: old ring still being demolished; an invalid value only loses the demolition, never the world.
                if (e.contains("oldRadius", Tag.TAG_INT) && e.getInt("oldRadius") >= 8 && e.getInt("oldRadius") <= 256) data.relocations.put(e.getUUID("settlement"), e.getInt("oldRadius"));
            }
            // 0.37 optional lists (absent in older files).
            for (var raw : tag.getList("lodges", Tag.TAG_COMPOUND)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("settlement")) data.lodges.add(e.getUUID("settlement"));
            }
            for (var raw : tag.getList("chapters", Tag.TAG_COMPOUND)) {
                var e = (CompoundTag) raw;
                if (!e.hasUUID("player")) throw new IllegalArgumentException("Invalid chapter owner");
                data.chapters.put(e.getUUID("player"), new dev.livingkingdoms.rpg.domain.ChapterProgress(e.getInt("accepted"), e.getLong("claimed"), e.getInt("kills")));
            }
        }
        if (data.kingdomXp.size() > MAX_KINGDOMS || data.boards.size() > MAX_PLAYERS || data.bonds.size() > MAX_BONDS
                || data.expeditions.size() > MAX_EXPEDITIONS) throw new IllegalArgumentException("RPG data exceeds caps");
        data.setDirty(schema < SCHEMA_VERSION);
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        var kingdoms = new ListTag();
        kingdomXp.forEach((id, xp) -> { var e = new CompoundTag(); e.putUUID("settlement", id); e.putLong("xp", xp); kingdoms.add(e); });
        tag.put("kingdoms", kingdoms);
        var list = new ListTag();
        boards.forEach((id, board) -> {
            var e = new CompoundTag(); e.putUUID("player", id);
            e.putLong("day", board.day()); e.putLong("week", board.week()); e.putInt("streak", board.streak());
            e.putLong("last_full_day", board.lastFullDay()); e.putBoolean("bonus_claimed", board.dailyBonusClaimed());
            var daily = new ListTag(); board.daily().forEach(c -> daily.add(contract(c))); e.put("daily", daily);
            if (board.weekly() != null) e.put("weekly", contract(board.weekly()));
            list.add(e);
        });
        tag.put("boards", list);
        var bondList = new ListTag();
        bonds.forEach((id, b) -> { var e = new CompoundTag(); e.putUUID("citizen", id); e.putInt("value", b.value()); e.putLong("talk", b.lastTalkDay());
            e.putLong("request", b.lastRequestDay()); e.putLong("gift", b.lastGiftDay()); e.putBoolean("devoted", b.devotedRewarded()); bondList.add(e); });
        tag.put("bonds", bondList);
        var expeditionList = new ListTag();
        expeditions.values().forEach(x -> { var e = new CompoundTag(); e.putUUID("citizen", x.citizen()); e.putUUID("settlement", x.settlement());
            e.putUUID("player", x.player()); e.putString("kind", x.kind().name()); e.putLong("started", x.startedAt()); e.putLong("ends", x.endsAt()); expeditionList.add(e); });
        tag.put("expeditions", expeditionList);
        var pendingList = new ListTag();
        pending.forEach((id, stacks) -> { var e = new CompoundTag(); e.putUUID("player", id); var items = new ListTag(); stacks.forEach(items::add); e.put("items", items); pendingList.add(e); });
        tag.put("pending", pendingList);
        var decorList = new ListTag();
        decor.forEach((id, bits) -> { var e = new CompoundTag(); e.putUUID("settlement", id); e.putInt("bits", bits); decorList.add(e); });
        tag.put("decor", decorList);
        var techniqueList = new ListTag();
        techniques.forEach((id, set) -> { var e = new CompoundTag(); e.putUUID("player", id); var known = new ListTag();
            set.forEach(t -> known.add(net.minecraft.nbt.StringTag.valueOf(t.id()))); e.put("known", known); techniqueList.add(e); });
        tag.put("techniques", techniqueList);
        var wallList = new ListTag();
        walls.forEach((id, w) -> { var e = new CompoundTag(); e.putUUID("settlement", id); e.putInt("tier", w.tier()); e.putInt("radius", w.radius());
            e.putInt("built", w.built()); e.putBoolean("complete", w.complete());
            var d = designs.get(id);
            if (d != null) { e.putInt("gates", d.gates()); e.putBoolean("towers", d.towers()); e.putBoolean("gatehouses", d.gatehouses()); }
            var old = relocations.get(id);
            if (old != null) e.putInt("oldRadius", old);
            wallList.add(e); });
        tag.put("walls", wallList);
        var lodgeList = new ListTag();
        for (var id : lodges) { var e = new CompoundTag(); e.putUUID("settlement", id); lodgeList.add(e); }
        tag.put("lodges", lodgeList);
        var chapterList = new ListTag();
        chapters.forEach((id, c) -> { var e = new CompoundTag(); e.putUUID("player", id); e.putInt("accepted", c.accepted()); e.putLong("claimed", c.claimed()); e.putInt("kills", c.kills()); chapterList.add(e); });
        tag.put("chapters", chapterList);
        return tag;
    }

    private static CompoundTag contract(Contract c) {
        var tag = new CompoundTag(); tag.putString("kind", c.kind().name()); tag.putString("target", c.target());
        tag.putInt("required", c.required()); tag.putInt("progress", c.progress()); tag.putBoolean("claimed", c.claimed());
        return tag;
    }

    private static Contract contract(CompoundTag tag) {
        return new Contract(Contract.Kind.valueOf(tag.getString("kind")), tag.getString("target"), tag.getInt("required"),
                tag.getInt("progress"), tag.getBoolean("claimed"));
    }

    private static ListTag list(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing RPG list: " + key);
        return tag.getList(key, Tag.TAG_COMPOUND);
    }
}

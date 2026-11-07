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
 */
public final class RpgSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_rpg";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_KINGDOMS = 65536, MAX_PLAYERS = 65536;
    private final Map<UUID, Long> kingdomXp = new LinkedHashMap<>();
    private final Map<UUID, ContractBoard> boards = new LinkedHashMap<>();

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
        if (data.kingdomXp.size() > MAX_KINGDOMS || data.boards.size() > MAX_PLAYERS) throw new IllegalArgumentException("RPG data exceeds caps");
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

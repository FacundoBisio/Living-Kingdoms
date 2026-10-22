package dev.livingkingdoms.war.persistence;

import dev.livingkingdoms.war.domain.KingdomStance;
import dev.livingkingdoms.war.domain.RenownLedger;
import dev.livingkingdoms.war.domain.RenownRank;
import dev.livingkingdoms.war.domain.WarFront;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * War fronts of NPC kingdoms, raids in flight and player renown. One per world, held by the Overworld data
 * storage; server thread only. New file in 0.20 ({@code <world>/data/livingkingdoms_war.dat}); older mod
 * versions ignore it. Every collection is capped.
 */
public final class WarSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_war";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_FRONTS = 4096;
    public static final int MAX_CAMPAIGNS = 256;
    public static final int MAX_PLAYERS = 65536;

    private final Map<UUID, WarFront> fronts = new LinkedHashMap<>();
    private final Map<UUID, Campaign> campaigns = new LinkedHashMap<>();
    private final Map<UUID, RenownLedger> renown = new LinkedHashMap<>();
    /** Derived index, rebuilt from fronts; never persisted. */
    private final Map<UUID, UUID> garrisons = new HashMap<>();

    /** A raid in flight: the warband party, the kingdom that sent it and the settlement it marches on. */
    public record Campaign(UUID kingdom, UUID target, long launchedAt) {
        public Campaign {
            Objects.requireNonNull(kingdom, "kingdom");
            Objects.requireNonNull(target, "target");
            if (launchedAt < 0) throw new IllegalArgumentException("Invalid campaign launch time");
        }
    }

    public static WarSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("War data must be accessed on the server thread");
        return getOrCreate(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    public static WarSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        // An unreadable existing file is never replaced by an empty store.
        Factory<WarSavedData> factory = new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat")))
                throw new IllegalStateException("Existing Living Kingdoms war data could not be loaded; "
                        + "refusing to overwrite it. Back up the world and inspect the server log.");
            return new WarSavedData();
        }, WarSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    // ---- fronts -------------------------------------------------------------------------------------

    public Optional<WarFront> front(UUID kingdom) { return Optional.ofNullable(fronts.get(Objects.requireNonNull(kingdom, "kingdom"))); }

    public List<UUID> frontIds() { return List.copyOf(fronts.keySet()); }

    public int frontCount() { return fronts.size(); }

    public boolean hostile(UUID kingdom) {
        WarFront front = kingdom == null ? null : fronts.get(kingdom);
        return front != null && front.stance() == KingdomStance.HOSTILE;
    }

    /** False when the store is full and the kingdom is new. */
    public boolean putFront(UUID kingdom, WarFront front) {
        Objects.requireNonNull(kingdom, "kingdom");
        Objects.requireNonNull(front, "front");
        WarFront previous = fronts.get(kingdom);
        if (previous == null && fronts.size() >= MAX_FRONTS) return false;
        if (front.equals(previous)) return true;
        if (previous != null && previous.garrisonParty() != null) garrisons.remove(previous.garrisonParty());
        if (front.garrisonParty() != null) {
            UUID owner = garrisons.get(front.garrisonParty());
            if (owner != null && !owner.equals(kingdom)) throw new IllegalArgumentException("Garrison already belongs to another kingdom");
            garrisons.put(front.garrisonParty(), kingdom);
        }
        fronts.put(kingdom, front);
        setDirty();
        return true;
    }

    public Optional<UUID> garrisonOwner(UUID party) { return Optional.ofNullable(garrisons.get(party)); }

    // ---- campaigns ----------------------------------------------------------------------------------

    public Optional<Campaign> campaign(UUID party) { return Optional.ofNullable(campaigns.get(party)); }

    public Map<UUID, Campaign> campaigns() { return Map.copyOf(campaigns); }

    public boolean hasCampaignAgainst(UUID target) {
        for (Campaign campaign : campaigns.values()) if (campaign.target().equals(target)) return true;
        return false;
    }

    public boolean addCampaign(UUID party, Campaign campaign) {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(campaign, "campaign");
        if (campaigns.containsKey(party) || campaigns.size() >= MAX_CAMPAIGNS) return false;
        campaigns.put(party, campaign);
        setDirty();
        return true;
    }

    public Optional<Campaign> removeCampaign(UUID party) {
        Campaign removed = campaigns.remove(party);
        if (removed != null) setDirty();
        return Optional.ofNullable(removed);
    }

    // ---- renown -------------------------------------------------------------------------------------

    public RenownLedger ledger(UUID player) {
        return renown.getOrDefault(Objects.requireNonNull(player, "player"), RenownLedger.empty());
    }

    public boolean putLedger(UUID player, RenownLedger ledger) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(ledger, "ledger");
        RenownLedger previous = renown.get(player);
        if (previous == null && renown.size() >= MAX_PLAYERS) return false;
        if (ledger.equals(previous)) return true;
        renown.put(player, ledger);
        setDirty();
        return true;
    }

    // ---- NBT ----------------------------------------------------------------------------------------

    public static WarSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema_version", Tag.TAG_INT)) throw new IllegalArgumentException("Missing war schema");
        int schema = tag.getInt("schema_version");
        if (schema < 1 || schema > SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported war schema: " + schema);
        WarSavedData data = new WarSavedData();
        ListTag fronts = list(tag, "fronts", MAX_FRONTS);
        for (int i = 0; i < fronts.size(); i++) {
            CompoundTag entry = fronts.getCompound(i);
            if (!entry.hasUUID("kingdom")) throw new IllegalArgumentException("Invalid war front kingdom");
            require(entry, "stance", Tag.TAG_STRING);
            require(entry, "strength", Tag.TAG_INT);
            require(entry, "next_raid", Tag.TAG_LONG);
            require(entry, "raids", Tag.TAG_INT);
            require(entry, "repelled", Tag.TAG_INT);
            require(entry, "next_garrison", Tag.TAG_LONG);
            require(entry, "standards", Tag.TAG_BYTE);
            UUID garrison = entry.hasUUID("garrison") ? entry.getUUID("garrison") : null;
            WarFront front = new WarFront(KingdomStance.fromId(entry.getString("stance")), entry.getInt("strength"),
                    entry.getLong("next_raid"), entry.getInt("raids"), entry.getInt("repelled"), garrison,
                    entry.getLong("next_garrison"), entry.getBoolean("standards"));
            UUID kingdom = entry.getUUID("kingdom");
            if (data.fronts.containsKey(kingdom)) throw new IllegalArgumentException("Duplicate war front");
            data.putFront(kingdom, front);
        }
        ListTag campaigns = list(tag, "campaigns", MAX_CAMPAIGNS);
        for (int i = 0; i < campaigns.size(); i++) {
            CompoundTag entry = campaigns.getCompound(i);
            if (!entry.hasUUID("party") || !entry.hasUUID("kingdom") || !entry.hasUUID("target"))
                throw new IllegalArgumentException("Invalid war campaign");
            require(entry, "launched", Tag.TAG_LONG);
            if (!data.addCampaign(entry.getUUID("party"), new Campaign(entry.getUUID("kingdom"), entry.getUUID("target"), entry.getLong("launched"))))
                throw new IllegalArgumentException("Duplicate war campaign");
        }
        ListTag players = list(tag, "renown", MAX_PLAYERS);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.hasUUID("player")) throw new IllegalArgumentException("Invalid renown player");
            require(entry, "points", Tag.TAG_INT);
            require(entry, "rewarded", Tag.TAG_STRING);
            UUID player = entry.getUUID("player");
            if (data.renown.containsKey(player)) throw new IllegalArgumentException("Duplicate renown player");
            data.renown.put(player, new RenownLedger(entry.getInt("points"), RenownRank.fromId(entry.getString("rewarded"))));
        }
        data.setDirty(schema < SCHEMA_VERSION);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag frontList = new ListTag();
        fronts.forEach((kingdom, front) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("kingdom", kingdom);
            entry.putString("stance", front.stance().id());
            entry.putInt("strength", front.strength());
            entry.putLong("next_raid", front.nextRaidAt());
            entry.putInt("raids", front.raidsLaunched());
            entry.putInt("repelled", front.raidsRepelled());
            if (front.garrisonParty() != null) entry.putUUID("garrison", front.garrisonParty());
            entry.putLong("next_garrison", front.nextGarrisonAt());
            entry.putBoolean("standards", front.standardsPlaced());
            frontList.add(entry);
        });
        tag.put("fronts", frontList);
        ListTag campaignList = new ListTag();
        campaigns.forEach((party, campaign) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("party", party);
            entry.putUUID("kingdom", campaign.kingdom());
            entry.putUUID("target", campaign.target());
            entry.putLong("launched", campaign.launchedAt());
            campaignList.add(entry);
        });
        tag.put("campaigns", campaignList);
        ListTag renownList = new ListTag();
        renown.forEach((player, ledger) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            entry.putInt("points", ledger.points());
            entry.putString("rewarded", ledger.rewarded().id());
            renownList.add(entry);
        });
        tag.put("renown", renownList);
        return tag;
    }

    private static ListTag list(CompoundTag tag, String key, int cap) {
        require(tag, key, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(key);
        if (list.size() > cap || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid war list: " + key);
        return list;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or invalid war field: " + key);
    }
}

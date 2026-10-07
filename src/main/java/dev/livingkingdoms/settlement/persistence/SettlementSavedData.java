package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One per world, held by Minecraft's Overworld data storage; access on the server thread. */
public final class SettlementSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_settlements";
    public static final int MAX_NEAREST_DISTANCE = 4096;
    private static final int SCHEMA_VERSION = 1;
    private static final int INDEX_BUCKET_SIZE = 256;
    private final Map<UUID, Settlement> settlements = new LinkedHashMap<>();
    // Derived from authoritative records, never serialized or shared between worlds.
    private final Map<String, Map<Bucket, List<UUID>>> alliedCenters = new HashMap<>();

    public static SettlementSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Settlement data must be accessed on the server thread");
        }
        return getOrCreate(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static SettlementSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        // Vanilla may catch read/deserialize failures and return null. Never treat an
        // unreadable existing save as a new world, because it could then be overwritten.
        Factory<SettlementSavedData> factory = new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat"))) {
                throw new IllegalStateException("Existing Living Kingdoms settlement data could not be loaded; "
                        + "refusing to overwrite it. Back up the world and inspect the server log.");
            }
            return new SettlementSavedData();
        }, SettlementSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    public List<Settlement> settlements() {
        return List.copyOf(settlements.values());
    }

    public Optional<Settlement> get(UUID id) {
        return Optional.ofNullable(settlements.get(Objects.requireNonNull(id, "id")));
    }

    /** Event-driven horizontal center lookup. Uses metadata only, without chunk or entity access. */
    public Optional<Settlement> nearestAllied(String dimension, int x, int z, int maxDistance) {
        Objects.requireNonNull(dimension, "dimension");
        if (maxDistance < 0 || maxDistance > MAX_NEAREST_DISTANCE) {
            throw new IllegalArgumentException("Nearest settlement distance must be between 0 and "
                    + MAX_NEAREST_DISTANCE);
        }
        Map<Bucket, List<UUID>> dimensionIndex = alliedCenters.get(dimension);
        if (dimensionIndex == null || dimensionIndex.isEmpty()) return Optional.empty();
        int minX = bucketCoordinate((long) x - maxDistance);
        int maxX = bucketCoordinate((long) x + maxDistance);
        int minZ = bucketCoordinate((long) z - maxDistance);
        int maxZ = bucketCoordinate((long) z + maxDistance);
        long nearestDistanceSquared = (long) maxDistance * maxDistance;
        Settlement nearest = null;
        for (int bucketX = minX; bucketX <= maxX; bucketX++) {
            for (int bucketZ = minZ; bucketZ <= maxZ; bucketZ++) {
                List<UUID> ids = dimensionIndex.get(new Bucket(bucketX, bucketZ));
                if (ids == null) continue;
                for (UUID id : ids) {
                    Settlement settlement = settlements.get(id);
                    long dx = (long) settlement.territory().x() - x;
                    long dz = (long) settlement.territory().z() - z;
                    // Reject before multiplication; this also protects extreme integer coordinates.
                    if (Math.abs(dx) > maxDistance || Math.abs(dz) > maxDistance) continue;
                    long distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared < nearestDistanceSquared
                            || (distanceSquared == nearestDistanceSquared
                            && (nearest == null || settlement.id().compareTo(nearest.id()) < 0))) {
                        nearest = settlement;
                        nearestDistanceSquared = distanceSquared;
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Optional<Settlement> at(String dimension, int x, int z) {
        return settlements.values().stream()
                .filter(settlement -> settlement.territory().contains(dimension, x, z)).findFirst();
    }

    public boolean overlaps(Territory territory) {
        return settlements.values().stream().anyMatch(settlement -> settlement.territory().overlaps(territory));
    }

    public void add(Settlement settlement) {
        Objects.requireNonNull(settlement, "settlement");
        if (settlements.containsKey(settlement.id())) {
            throw new IllegalArgumentException("Duplicate settlement UUID: " + settlement.id());
        }
        if (overlaps(settlement.territory())) {
            throw new IllegalArgumentException("Settlement territory overlaps an existing settlement");
        }
        settlements.put(settlement.id(), settlement);
        indexAlliedCenter(settlement);
        setDirty();
    }

    private void indexAlliedCenter(Settlement settlement) {
        if (!settlement.faction().isAllied()) return;
        Territory territory = settlement.territory();
        Bucket bucket = new Bucket(bucketCoordinate(territory.x()), bucketCoordinate(territory.z()));
        alliedCenters.computeIfAbsent(territory.dimension(), ignored -> new HashMap<>())
                .computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(settlement.id());
    }

    private static int bucketCoordinate(long coordinate) {
        return Math.toIntExact(Math.floorDiv(coordinate, INDEX_BUCKET_SIZE));
    }

    private record Bucket(int x, int z) {}

    public static SettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementNbt.require(tag, "schema_version", Tag.TAG_INT);
        if (tag.getInt("schema_version") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Living Kingdoms settlement schema: "
                    + tag.getInt("schema_version"));
        }
        SettlementNbt.require(tag, "settlements", Tag.TAG_LIST);
        ListTag entries = (ListTag) tag.get("settlements");
        if (!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Settlement entries must be compounds");
        }
        SettlementSavedData data = new SettlementSavedData();
        for (int i = 0; i < entries.size(); i++) {
            data.add(SettlementNbt.read(entries.getCompound(i)));
        }
        data.setDirty(false);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag entries = new ListTag();
        settlements.values().forEach(settlement -> entries.add(SettlementNbt.write(settlement)));
        tag.put("settlements", entries);
        return tag;
    }
}

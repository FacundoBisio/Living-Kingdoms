package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
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
    private static final int SCHEMA_VERSION = 3;
    private static final int INDEX_BUCKET_SIZE = 256;
    private final Map<UUID, Settlement> settlements = new LinkedHashMap<>();
    private final Map<UUID, SettlementLayoutMetadata> layouts = new HashMap<>();
    // Derived from authoritative records, never serialized or shared between worlds.
    private final Map<String, Map<Bucket, List<UUID>>> alliedCenters = new HashMap<>();
    private final Map<String, List<UUID>> oversizedAlliedTerritories = new HashMap<>();

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

    public Optional<SettlementLayoutMetadata> layout(UUID id) { return Optional.ofNullable(layouts.get(id)); }

    public boolean replace(Settlement expected, Settlement replacement) {
        if (!expected.id().equals(replacement.id()) || !expected.territory().equals(replacement.territory()))
            throw new IllegalArgumentException("Settlement identity and territory cannot change");
        if (!settlements.replace(expected.id(), expected, replacement)) return false;
        setDirty();
        return true;
    }

    public void add(Settlement settlement, SettlementLayoutMetadata layout) {
        layout.validate(settlement.territory());
        add(settlement);
        layouts.put(settlement.id(), layout);
    }

    /** Called only after planning and reversible placement; does not change population or quest identity. */
    public void updateLayout(UUID id, SettlementLayoutMetadata layout) {
        Settlement settlement = get(id).orElseThrow(() -> new IllegalArgumentException("Unknown settlement"));
        layout.validate(settlement.territory());
        layouts.put(id, layout);
        setDirty();
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

    /** Natural encounter exclusion includes every allied territory radius and a group-sized margin. */
    public boolean nearAlliedTerritory(String dimension, int x, int z, int buffer) {
        if (buffer < 0 || buffer > 256) throw new IllegalArgumentException("Invalid territory buffer");
        Map<Bucket, List<UUID>> index = alliedCenters.get(Objects.requireNonNull(dimension));
        if (index == null) return false;
        int searchRange = 1024 + buffer; // Configured territory maximum; larger imported records are checked separately.
        for (int bx = bucketCoordinate((long) x - searchRange); bx <= bucketCoordinate((long) x + searchRange); bx++) {
            for (int bz = bucketCoordinate((long) z - searchRange); bz <= bucketCoordinate((long) z + searchRange); bz++) {
                List<UUID> ids = index.get(new Bucket(bx, bz));
                if (ids == null) continue;
                for (UUID id : ids) {
                    Territory territory = settlements.get(id).territory();
                    long radius = (long) territory.radius() + buffer;
                    long dx = (long) x - territory.x(), dz = (long) z - territory.z();
                    if (Math.abs(dx) <= radius && Math.abs(dz) <= radius && dx * dx + dz * dz <= radius * radius) return true;
                }
            }
        }
        for (UUID id : oversizedAlliedTerritories.getOrDefault(dimension, List.of())) {
            Territory territory = settlements.get(id).territory();
            double dx = (double) x - territory.x(), dz = (double) z - territory.z();
            double radius = (double) territory.radius() + buffer;
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
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

    /** Only for a failed synchronous establishment, before it becomes visible to gameplay. */
    public void rollbackEstablishment(Settlement expected) {
        if (!expected.equals(settlements.get(expected.id()))) throw new IllegalStateException("Establishment changed during rollback");
        settlements.remove(expected.id());
        layouts.remove(expected.id());
        var index = alliedCenters.get(expected.territory().dimension());
        if (index != null) {
            index.values().forEach(ids -> ids.remove(expected.id()));
            index.values().removeIf(List::isEmpty);
        }
        oversizedAlliedTerritories.getOrDefault(expected.territory().dimension(), new ArrayList<>()).remove(expected.id());
        setDirty();
    }

    private void indexAlliedCenter(Settlement settlement) {
        if (!settlement.faction().isAllied()) return;
        Territory territory = settlement.territory();
        if (territory.radius() > 1024) oversizedAlliedTerritories.computeIfAbsent(territory.dimension(), ignored -> new ArrayList<>()).add(settlement.id());
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
        int schema = tag.getInt("schema_version");
        if (schema < 1 || schema > SCHEMA_VERSION) {
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
            CompoundTag entry = entries.getCompound(i);
            Settlement settlement = SettlementNbt.read(entry, schema);
            if (entry.contains("layout")) {
                SettlementNbt.require(entry, "layout", Tag.TAG_COMPOUND);
                data.add(settlement, SettlementLayoutNbt.read(entry.getCompound("layout")));
            } else data.add(settlement);
        }
        data.setDirty(schema < SCHEMA_VERSION);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag entries = new ListTag();
        settlements.values().forEach(settlement -> {
            CompoundTag entry = SettlementNbt.write(settlement);
            SettlementLayoutMetadata layout = layouts.get(settlement.id());
            if (layout != null) entry.put("layout", SettlementLayoutNbt.write(layout));
            entries.add(entry);
        });
        tag.put("settlements", entries);
        return tag;
    }
}

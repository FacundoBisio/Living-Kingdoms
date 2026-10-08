package dev.livingkingdoms.citizen.persistence;

import dev.livingkingdoms.citizen.domain.Citizen;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.domain.Housing;
import dev.livingkingdoms.citizen.domain.HousingStatus;
import dev.livingkingdoms.citizen.domain.HousingSummary;
import dev.livingkingdoms.citizen.domain.ImmigrationCandidate;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.structure.BuildingKind;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Server-owned identity, housing and shared immigration requests. Vanilla persists the entities themselves. */
public final class CitizenSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_citizens";
    public static final int MAX_CITIZENS = 100_000;
    public static final int MAX_HOUSES = 65_536;
    public static final int MAX_CANDIDATES = 16_384;
    private static final int SCHEMA_VERSION = 1;
    private final Map<UUID, Citizen> citizens = new LinkedHashMap<>();
    private final Map<UUID, UUID> entityToCitizen = new HashMap<>();
    private final Map<UUID, Set<UUID>> settlementCitizens = new HashMap<>();
    private final Map<UUID, Housing> houses = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> settlementHouses = new HashMap<>();
    private final Map<UUID, ImmigrationCandidate> candidates = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> settlementCandidates = new HashMap<>();
    private final Map<UUID, Long> schedules = new LinkedHashMap<>();
    private final Set<UUID> initialized = new LinkedHashSet<>();
    private MinecraftServer owner;
    private Thread ownerThread;

    public CitizenSavedData() {}

    // Bound standalone stores let persistence tests exercise the same access rule without creating a server.
    CitizenSavedData(Thread ownerThread) { this.ownerThread = Objects.requireNonNull(ownerThread); }

    public static CitizenSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Citizen data belongs to the server thread");
        CitizenSavedData data = getOrCreate(server.overworld().getDataStorage(),
                server.getWorldPath(LevelResource.ROOT).resolve("data"));
        if (data.owner != null && data.owner != server) throw new IllegalStateException("Citizen data belongs to another server");
        data.owner = server;
        data.ownerThread = Thread.currentThread();
        return data;
    }

    static CitizenSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        Factory<CitizenSavedData> factory = new Factory<>(() -> {
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat")))
                throw new IllegalStateException("Existing Living Kingdoms citizen data could not be loaded; refusing to overwrite it.");
            return new CitizenSavedData();
        }, CitizenSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    private void checkThread() {
        if ((ownerThread != null && ownerThread != Thread.currentThread()) || (owner != null && !owner.isSameThread()))
            throw new IllegalStateException("Citizen data belongs to the server thread");
    }

    public Optional<Citizen> citizen(UUID id) { checkThread(); return Optional.ofNullable(citizens.get(Objects.requireNonNull(id))); }

    public Optional<Citizen> byEntity(UUID entity) {
        checkThread();
        UUID id = entityToCitizen.get(Objects.requireNonNull(entity));
        return id == null ? Optional.empty() : Optional.of(citizens.get(id));
    }

    public List<Citizen> citizens(UUID settlement) {
        checkThread();
        return settlementCitizens.getOrDefault(Objects.requireNonNull(settlement), Set.of()).stream().map(citizens::get).toList();
    }

    /** Duplicate registration is harmless; neither identity nor an existing entity can be stolen. */
    public boolean register(Citizen citizen) {
        checkThread(); Objects.requireNonNull(citizen);
        if (citizens.containsKey(citizen.id()) || entityToCitizen.containsKey(citizen.entityId())) return false;
        if (citizens.size() >= MAX_CITIZENS) return false;
        if (candidates.containsKey(citizen.id())) return false;
        requireHome(citizen);
        insertCitizen(citizen);
        setDirty();
        return true;
    }

    private void insertCitizen(Citizen citizen) {
        citizens.put(citizen.id(), citizen);
        entityToCitizen.put(citizen.entityId(), citizen.id());
        settlementCitizens.computeIfAbsent(citizen.settlementId(), ignored -> new LinkedHashSet<>()).add(citizen.id());
    }

    private void requireHome(Citizen citizen) {
        if (citizen.homeId() == null) return;
        Housing house = houses.get(citizen.homeId());
        if (house == null || !house.settlementId().equals(citizen.settlementId()) || house.status() != HousingStatus.ACTIVE
                || occupancy(house.id()) >= house.capacity()) throw new IllegalArgumentException("Citizen home is unavailable");
    }

    public boolean markDead(UUID entity) {
        checkThread();
        Citizen before = byEntity(entity).orElse(null);
        if (before == null || before.state() == CitizenState.DEAD) return false;
        citizens.put(before.id(), before.withState(CitizenState.DEAD));
        setDirty();
        assignHomes(before.settlementId());
        return true;
    }

    public int population(UUID settlement) {
        return (int) citizens(settlement).stream().filter(c -> c.state() == CitizenState.ACTIVE).count();
    }

    public Optional<Housing> housing(UUID id) { checkThread(); return Optional.ofNullable(houses.get(Objects.requireNonNull(id))); }

    public List<Housing> houses(UUID settlement) {
        checkThread();
        return settlementHouses.getOrDefault(Objects.requireNonNull(settlement), Set.of()).stream().map(houses::get).toList();
    }

    public List<Citizen> assignedResidents(UUID houseId) {
        Housing house = housing(houseId).orElse(null);
        if (house == null) return List.of();
        return citizens(house.settlementId()).stream()
                .filter(c -> c.state() == CitizenState.ACTIVE && houseId.equals(c.homeId())).toList();
    }

    public int occupancy(UUID houseId) { return assignedResidents(houseId).size(); }

    public HousingSummary summary(UUID settlement) {
        int total = 0, occupied = 0;
        for (Housing house : houses(settlement)) {
            if (house.status() == HousingStatus.ACTIVE) { total += house.capacity(); occupied += occupancy(house.id()); }
        }
        return new HousingSummary(total, occupied, total - occupied);
    }

    public void synchronizeHousing(Settlement settlement, SettlementLayoutMetadata layout,
                                   ToIntFunction<SettlementLayoutMetadata.Building> capacity) {
        synchronizeHousing(settlement, layout, capacity, false);
    }

    /** Called on initialization/construction/config refresh, never every tick. Missing layout means no registered houses. */
    public void synchronizeHousing(Settlement settlement, SettlementLayoutMetadata layout,
                                   ToIntFunction<SettlementLayoutMetadata.Building> capacity, boolean includeTemporaryShelters) {
        checkThread(); Objects.requireNonNull(settlement); Objects.requireNonNull(capacity);
        Map<UUID, Housing> desired = new LinkedHashMap<>();
        if (layout != null) {
            for (SettlementLayoutMetadata.Building building : layout.buildings()) {
                if (!isHouse(building.kind()) && !(includeTemporaryShelters && building.kind() == BuildingKind.FOUNDING_CAMP)) continue;
                UUID id = Housing.identity(settlement.id(), building);
                Housing house = new Housing(id, settlement.id(), settlement.territory().dimension(), building.template(),
                        building.origin(), building.entrance(), capacity.applyAsInt(building), HousingStatus.ACTIVE);
                if (desired.put(id, house) != null) throw new IllegalArgumentException("Duplicate housing metadata");
            }
        }
        List<Housing> current = houses(settlement.id());
        if ((long) houses.size() - current.size() + desired.size() > MAX_HOUSES)
            throw new IllegalStateException("Living Kingdoms housing metadata limit reached");
        for (Housing house : desired.values()) {
            Housing existing = houses.get(house.id());
            if (existing != null && !existing.settlementId().equals(settlement.id()))
                throw new IllegalArgumentException("Housing UUID belongs to another settlement");
        }
        if (current.equals(List.copyOf(desired.values()))) { assignHomes(settlement.id()); return; }
        for (Housing house : current) houses.remove(house.id());
        houses.putAll(desired);
        if (desired.isEmpty()) settlementHouses.remove(settlement.id());
        else settlementHouses.put(settlement.id(), new LinkedHashSet<>(desired.keySet()));
        reconcileHomes(settlement.id());
        setDirty();
        assignHomes(settlement.id());
    }

    private static boolean isHouse(BuildingKind kind) {
        return kind == BuildingKind.HOUSE || kind == BuildingKind.HOUSE_VARIANT || kind == BuildingKind.HOUSE_THIRD;
    }

    private void reconcileHomes(UUID settlement) {
        Map<UUID, Integer> used = new HashMap<>();
        for (Citizen citizen : citizens(settlement)) {
            if (citizen.homeId() == null) continue;
            Housing house = houses.get(citizen.homeId());
            int already = used.getOrDefault(citizen.homeId(), 0);
            if (house == null || house.status() != HousingStatus.ACTIVE || already >= house.capacity())
                citizens.put(citizen.id(), citizen.withHome(null));
            else used.put(house.id(), already + 1);
        }
    }

    /** Existing residents take available places before new requests can consume them. */
    public void assignHomes(UUID settlement) {
        checkThread();
        List<Housing> available = houses(settlement).stream().filter(h -> h.status() == HousingStatus.ACTIVE).toList();
        Map<UUID, Integer> used = new HashMap<>();
        for (Citizen citizen : citizens(settlement)) {
            if (citizen.state() == CitizenState.ACTIVE && citizen.homeId() != null) used.merge(citizen.homeId(), 1, Integer::sum);
        }
        for (Citizen citizen : citizens(settlement)) {
            if (citizen.state() != CitizenState.ACTIVE || citizen.homeId() != null) continue;
            for (Housing house : available) {
                int count = used.getOrDefault(house.id(), 0);
                if (count < house.capacity()) {
                    citizens.put(citizen.id(), citizen.withHome(house.id()));
                    used.put(house.id(), count + 1);
                    setDirty();
                    break;
                }
            }
        }
    }

    public boolean initialized(UUID settlement) { checkThread(); return initialized.contains(Objects.requireNonNull(settlement)); }
    public void markInitialized(UUID settlement) { checkThread(); if (initialized.add(Objects.requireNonNull(settlement))) setDirty(); }

    public List<ImmigrationCandidate> candidates(UUID settlement) {
        checkThread();
        return settlementCandidates.getOrDefault(Objects.requireNonNull(settlement), Set.of()).stream().map(candidates::get).toList();
    }

    public Optional<ImmigrationCandidate> candidate(UUID id) {
        checkThread(); return Optional.ofNullable(candidates.get(Objects.requireNonNull(id)));
    }

    public boolean addCandidate(ImmigrationCandidate candidate) {
        checkThread(); Objects.requireNonNull(candidate);
        if (candidates.containsKey(candidate.id()) || citizens.containsKey(candidate.id())) return false;
        if (candidates.size() >= MAX_CANDIDATES) return false;
        if (candidates(candidate.settlementId()).size() >= 16) return false;
        candidates.put(candidate.id(), candidate);
        settlementCandidates.computeIfAbsent(candidate.settlementId(), ignored -> new LinkedHashSet<>()).add(candidate.id());
        setDirty(); return true;
    }

    public Optional<ImmigrationCandidate> removeCandidate(UUID id) {
        checkThread();
        ImmigrationCandidate removed = candidates.remove(Objects.requireNonNull(id));
        if (removed == null) return Optional.empty();
        Set<UUID> ids = settlementCandidates.get(removed.settlementId());
        ids.remove(id);
        if (ids.isEmpty()) settlementCandidates.remove(removed.settlementId());
        setDirty(); return Optional.of(removed);
    }

    public int expireCandidates(UUID settlement, long now) {
        checkThread(); requireTime(now);
        int expired = 0;
        for (ImmigrationCandidate candidate : candidates(settlement)) {
            if (candidate.expired(now)) { removeCandidate(candidate.id()); expired++; }
        }
        return expired;
    }

    public long nextCheck(UUID settlement) { checkThread(); return schedules.getOrDefault(Objects.requireNonNull(settlement), 0L); }

    public void schedule(UUID settlement, long tick) {
        checkThread(); requireTime(tick);
        Long old = schedules.put(Objects.requireNonNull(settlement), tick);
        if (old == null || old != tick) setDirty();
    }

    /** One server-thread transaction reserves a place, consumes the shared request and registers its permanent receipt. */
    public Optional<Citizen> acceptCandidate(UUID settlement, UUID candidateId, UUID entityId, long now) {
        return acceptCandidate(settlement, candidateId, entityId, null, now);
    }

    /** Loaded spawn validation can choose a particular free house without being blocked by an unloaded house. */
    public Optional<Citizen> acceptCandidate(UUID settlement, UUID candidateId, UUID entityId, UUID homeId, long now) {
        checkThread(); Objects.requireNonNull(settlement); Objects.requireNonNull(entityId); requireTime(now);
        ImmigrationCandidate candidate = candidate(candidateId).orElse(null);
        if (candidate == null || !candidate.settlementId().equals(settlement) || now < candidate.createdAt()
                || candidate.expired(now) || citizens.containsKey(candidateId) || entityToCitizen.containsKey(entityId)) return Optional.empty();
        if (citizens.size() >= MAX_CITIZENS) return Optional.empty();
        assignHomes(settlement);
        Housing free = houses(settlement).stream().filter(h -> (homeId == null || h.id().equals(homeId))
                        && h.status() == HousingStatus.ACTIVE && occupancy(h.id()) < h.capacity())
                .findFirst().orElse(null);
        if (free == null) return Optional.empty();
        // Occupation remains UNASSIGNED; candidate preferences are future-facing metadata, not active professions.
        Citizen citizen = new Citizen(candidate.id(), entityId, settlement, candidate.name(), candidate.level(),
                CitizenRole.UNASSIGNED, free.id(), CitizenState.ACTIVE, now);
        removeCandidate(candidate.id());
        insertCitizen(citizen);
        setDirty(); return Optional.of(citizen);
    }

    /** Only an exact, just-created acceptance may be rolled back when adding the vanilla entity fails. */
    public boolean rollbackAcceptance(Citizen accepted, ImmigrationCandidate original) {
        checkThread(); Objects.requireNonNull(accepted); Objects.requireNonNull(original);
        if (!accepted.id().equals(original.id()) || !accepted.settlementId().equals(original.settlementId())
                || !accepted.equals(citizens.get(accepted.id())) || candidates.containsKey(original.id())) return false;
        citizens.remove(accepted.id()); entityToCitizen.remove(accepted.entityId());
        Set<UUID> ids = settlementCitizens.get(accepted.settlementId()); ids.remove(accepted.id());
        if (ids.isEmpty()) settlementCitizens.remove(accepted.settlementId());
        if (!addCandidate(original)) throw new IllegalStateException("Could not restore immigrant request");
        setDirty(); return true;
    }

    private static void requireTime(long tick) { if (tick < 0) throw new IllegalArgumentException("Negative citizen timestamp"); }

    public static CitizenSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CitizenNbt.require(tag, "schema_version", Tag.TAG_INT);
        if (tag.getInt("schema_version") != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported citizen schema");
        CitizenSavedData data = new CitizenSavedData();
        ListTag homes = CitizenNbt.compoundList(tag, "housing");
        if (homes.size() > MAX_HOUSES) throw new IllegalArgumentException("Too much housing metadata");
        for (int i = 0; i < homes.size(); i++) {
            Housing house = CitizenNbt.readHousing(homes.getCompound(i));
            if (data.houses.putIfAbsent(house.id(), house) != null) throw new IllegalArgumentException("Duplicate housing identity");
            data.settlementHouses.computeIfAbsent(house.settlementId(), ignored -> new LinkedHashSet<>()).add(house.id());
        }
        ListTag people = CitizenNbt.compoundList(tag, "citizens");
        if (people.size() > MAX_CITIZENS) throw new IllegalArgumentException("Too many citizen records");
        for (int i = 0; i < people.size(); i++) {
            Citizen citizen = CitizenNbt.readCitizen(people.getCompound(i));
            if (!data.register(citizen)) throw new IllegalArgumentException("Duplicate citizen identity or entity");
        }
        ListTag pending = CitizenNbt.compoundList(tag, "candidates");
        if (pending.size() > MAX_CANDIDATES) throw new IllegalArgumentException("Too many immigration requests");
        for (int i = 0; i < pending.size(); i++)
            if (!data.addCandidate(CitizenNbt.readCandidate(pending.getCompound(i)))) throw new IllegalArgumentException("Invalid candidate roster");
        ListTag schedule = CitizenNbt.compoundList(tag, "schedule");
        for (int i = 0; i < schedule.size(); i++) {
            CompoundTag entry = schedule.getCompound(i);
            UUID settlement = CitizenNbt.uuid(entry, "settlement"); CitizenNbt.require(entry, "next_check", Tag.TAG_LONG);
            long next = entry.getLong("next_check"); requireTime(next);
            if (data.schedules.putIfAbsent(settlement, next) != null) throw new IllegalArgumentException("Duplicate immigration schedule");
        }
        ListTag migrations = CitizenNbt.compoundList(tag, "initialized");
        for (int i = 0; i < migrations.size(); i++)
            if (!data.initialized.add(CitizenNbt.uuid(migrations.getCompound(i), "settlement")))
                throw new IllegalArgumentException("Duplicate citizen initialization receipt");
        data.setDirty(false); return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag homes = new ListTag(); houses.values().forEach(h -> homes.add(CitizenNbt.writeHousing(h))); tag.put("housing", homes);
        ListTag people = new ListTag(); citizens.values().forEach(c -> people.add(CitizenNbt.writeCitizen(c))); tag.put("citizens", people);
        ListTag pending = new ListTag(); candidates.values().forEach(c -> pending.add(CitizenNbt.writeCandidate(c))); tag.put("candidates", pending);
        ListTag schedule = new ListTag();
        schedules.forEach((id, next) -> { CompoundTag entry = new CompoundTag(); entry.putUUID("settlement", id);
            entry.putLong("next_check", next); schedule.add(entry); }); tag.put("schedule", schedule);
        ListTag migrations = new ListTag(); initialized.forEach(id -> { CompoundTag entry = new CompoundTag();
            entry.putUUID("settlement", id); migrations.add(entry); }); tag.put("initialized", migrations);
        return tag;
    }
}

package dev.livingkingdoms.encounter.persistence;

import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyState;
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

/** Global server-owned metadata; member events resolve by UUID without entity/world scans. */
public final class EncounterSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_encounters";
    private static final int SCHEMA_VERSION = 2;
    private static final int INDEX_BUCKET_SIZE = 256;
    public static final int MAX_PARTICIPANTS = 64;
    public static final long PARTICIPATION_EXPIRY_TICKS = 6_000;
    private static final double MAX_DAMAGE = 1_000_000;
    private final Map<UUID, HostileParty> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberToParty = new HashMap<>();
    private final Map<UUID, Tracking> tracking = new HashMap<>();
    private final Map<String, Map<Bucket, Set<UUID>>> activeOrigins = new HashMap<>();
    private final Map<String, Long> naturalSchedule = new LinkedHashMap<>();

    public static EncounterSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Encounter data must be accessed on the server thread");
        }
        return getOrCreate(server.overworld().getDataStorage(),
                server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static EncounterSavedData getOrCreate(DimensionDataStorage storage, Path directory) {
        Factory<EncounterSavedData> factory = new Factory<>(() -> {
            // Vanilla may return null after swallowing an invalid save. Refuse a fresh
            // empty replacement when any existing file could not be read safely.
            if (!Files.notExists(directory.resolve(DATA_NAME + ".dat"))) {
                throw new IllegalStateException("Existing Living Kingdoms encounter data could not be loaded; "
                        + "refusing to overwrite it. Back up the world and inspect the server log.");
            }
            return new EncounterSavedData();
        }, EncounterSavedData::load);
        return storage.computeIfAbsent(factory, DATA_NAME);
    }

    public List<HostileParty> parties() {
        return List.copyOf(parties.values());
    }

    public int trackedPartyCount() {
        return parties.size();
    }

    /** Active origin centers only: regional limits and future quests need no loaded entities. */
    public List<HostileParty> activeNearby(String dimension, int x, int z, int radius) {
        Objects.requireNonNull(dimension, "dimension");
        if (radius < 0 || radius > 4096) throw new IllegalArgumentException("Encounter lookup radius must be 0..4096");
        Map<Bucket, Set<UUID>> index = activeOrigins.get(dimension);
        if (index == null) return List.of();
        List<HostileParty> result = new ArrayList<>();
        for (int bx = bucket((long) x - radius); bx <= bucket((long) x + radius); bx++) {
            for (int bz = bucket((long) z - radius); bz <= bucket((long) z + radius); bz++) {
                Set<UUID> ids = index.get(new Bucket(bx, bz));
                if (ids == null) continue;
                for (UUID id : ids) {
                    HostileParty party = parties.get(id);
                    long dx = (long) party.origin().x() - x;
                    long dz = (long) party.origin().z() - z;
                    if (Math.abs(dx) <= radius && Math.abs(dz) <= radius
                            && dx * dx + dz * dz <= (long) radius * radius) result.add(party);
                }
            }
        }
        return List.copyOf(result);
    }

    public long nextNaturalAttempt(String dimension) {
        requireDimension(dimension);
        return naturalSchedule.getOrDefault(dimension, 0L);
    }

    public void scheduleNaturalAttempt(String dimension, long nextTick) {
        requireDimension(dimension);
        requireTick(nextTick);
        Long previous = naturalSchedule.put(dimension, nextTick);
        if (previous == null || previous != nextTick) setDirty();
    }

    public Optional<HostileParty> get(UUID partyId) {
        return Optional.ofNullable(parties.get(Objects.requireNonNull(partyId, "partyId")));
    }

    /** Includes previously defeated members so duplicate death notifications remain harmless. */
    public Optional<HostileParty> forMember(UUID entityId) {
        UUID partyId = memberToParty.get(Objects.requireNonNull(entityId, "entityId"));
        return partyId == null ? Optional.empty() : Optional.of(parties.get(partyId));
    }

    public void add(HostileParty party) {
        addInternal(party, -1);
    }

    public void add(HostileParty party, long createdAt) {
        requireTick(createdAt);
        addInternal(party, createdAt);
    }

    private void addInternal(HostileParty party, long createdAt) {
        Objects.requireNonNull(party, "party");
        if (parties.containsKey(party.id())) {
            throw new IllegalArgumentException("Duplicate hostile party UUID: " + party.id());
        }
        for (UUID member : party.memberIds()) {
            if (memberToParty.containsKey(member)) {
                throw new IllegalArgumentException("Entity already belongs to a hostile party: " + member);
            }
        }
        parties.put(party.id(), party);
        party.memberIds().forEach(member -> memberToParty.put(member, party.id()));
        tracking.put(party.id(), new Tracking(createdAt, -1));
        indexActive(party);
        setDirty();
    }

    /** Damage events reach here only after health damage; dead and unrelated roster members are ignored. */
    public boolean recordContribution(UUID entityId, UUID playerId, float damage, long now) {
        Objects.requireNonNull(playerId, "playerId");
        requireTick(now);
        if (!Float.isFinite(damage) || damage <= 0) return false;
        HostileParty party = forMember(entityId).orElse(null);
        if (party == null || !party.remainingMembers().contains(entityId)) return false;
        Tracking entry = tracking.get(party.id());
        if (pruneParticipants(entry, now)) setDirty();
        Participation before = entry.participants.get(playerId);
        if (before == null && entry.participants.size() >= MAX_PARTICIPANTS) return false;
        double total = Math.min(MAX_DAMAGE, (before == null ? 0 : before.damage) + damage);
        entry.participants.put(playerId, new Participation(total, now));
        setDirty();
        return true;
    }

    public Set<UUID> eligibleParticipants(UUID partyId, long now, double minimumDamage, long window) {
        Objects.requireNonNull(partyId, "partyId");
        requireTick(now);
        if (!Double.isFinite(minimumDamage) || minimumDamage <= 0 || window < 0) {
            throw new IllegalArgumentException("Invalid contribution threshold or window");
        }
        Tracking entry = tracking.get(partyId);
        if (entry == null) return Set.of();
        Set<UUID> eligible = new LinkedHashSet<>();
        entry.participants.forEach((player, contribution) -> {
            if (contribution.damage >= minimumDamage && now >= contribution.lastDamageTick
                    && now - contribution.lastDamageTick <= window) eligible.add(player);
        });
        return Set.copyOf(eligible);
    }

    /** Returns only the first full-party defeat, the point where gameplay may grant a receipt. */
    public Optional<HostileParty> recordDeath(UUID entityId) {
        return recordDeathInternal(entityId, -1);
    }

    public Optional<HostileParty> recordDeath(UUID entityId, long now) {
        requireTick(now);
        return recordDeathInternal(entityId, now);
    }

    private Optional<HostileParty> recordDeathInternal(UUID entityId, long now) {
        HostileParty before = forMember(entityId).orElse(null);
        if (before == null) return Optional.empty();
        HostileParty after = before.withMemberDeath(entityId);
        if (after == before) return Optional.empty();
        parties.put(after.id(), after);
        setDirty();
        if (after.state() == PartyState.DEFEATED) {
            unindexActive(before);
            Tracking entry = tracking.get(after.id());
            entry.finishedAt = now < 0 ? -1 : Math.max(now, entry.createdAt);
        }
        return before.state() == PartyState.ALIVE && after.state() == PartyState.DEFEATED
                ? Optional.of(after) : Optional.empty();
    }

    /** Tracks vanilla conversion events without treating unloaded or transformed mobs as dead. */
    public boolean replaceMember(UUID oldMember, UUID replacement) {
        Objects.requireNonNull(oldMember, "oldMember");
        Objects.requireNonNull(replacement, "replacement");
        HostileParty before = forMember(oldMember).orElse(null);
        if (before == null || !before.remainingMembers().contains(oldMember)) return false;
        if (oldMember.equals(replacement)) return true;
        if (memberToParty.containsKey(replacement)) return false;
        HostileParty after = before.replaceMember(oldMember, replacement);
        parties.put(after.id(), after);
        memberToParty.remove(oldMember);
        memberToParty.put(replacement, after.id());
        setDirty();
        return true;
    }

    /** Rollbacks and periodic retirement remove every derived index; removed entities can no longer reward. */
    public Optional<HostileParty> remove(UUID partyId) {
        HostileParty party = parties.remove(Objects.requireNonNull(partyId, "partyId"));
        if (party == null) return Optional.empty();
        party.memberIds().forEach(memberToParty::remove);
        tracking.remove(party.id());
        unindexActive(party);
        setDirty();
        return Optional.of(party);
    }

    /** Occasional metadata maintenance. Unloading does not imply death or grant reputation. */
    public List<HostileParty> cleanup(long now, long completedRetention, long activeLifetime) {
        requireTick(now);
        if (completedRetention < 0 || activeLifetime < 1) throw new IllegalArgumentException("Invalid encounter retention");
        List<HostileParty> removed = new ArrayList<>();
        for (HostileParty party : List.copyOf(parties.values())) {
            Tracking entry = tracking.get(party.id());
            if (entry.createdAt < 0) {
                entry.createdAt = entry.finishedAt >= 0 ? Math.min(now, entry.finishedAt) : now;
                setDirty();
            }
            if (party.state() == PartyState.DEFEATED && entry.finishedAt < 0) {
                entry.finishedAt = Math.max(now, entry.createdAt);
                setDirty();
            }
            if (pruneParticipants(entry, now)) setDirty();
            long since = party.state() == PartyState.DEFEATED ? entry.finishedAt : entry.createdAt;
            long duration = party.state() == PartyState.DEFEATED ? completedRetention : activeLifetime;
            if (now >= since && now - since >= duration) removed.add(remove(party.id()).orElseThrow());
        }
        return List.copyOf(removed);
    }

    private static boolean pruneParticipants(Tracking entry, long now) {
        return entry.participants.values().removeIf(participant -> now >= participant.lastDamageTick
                && now - participant.lastDamageTick > PARTICIPATION_EXPIRY_TICKS);
    }

    private void indexActive(HostileParty party) {
        if (party.state() != PartyState.ALIVE) return;
        activeOrigins.computeIfAbsent(party.origin().dimension(), ignored -> new HashMap<>())
                .computeIfAbsent(new Bucket(bucket(party.origin().x()), bucket(party.origin().z())),
                        ignored -> new LinkedHashSet<>()).add(party.id());
    }

    private void unindexActive(HostileParty party) {
        Map<Bucket, Set<UUID>> dimension = activeOrigins.get(party.origin().dimension());
        if (dimension == null) return;
        Bucket bucket = new Bucket(bucket(party.origin().x()), bucket(party.origin().z()));
        Set<UUID> ids = dimension.get(bucket);
        if (ids != null) {
            ids.remove(party.id());
            if (ids.isEmpty()) dimension.remove(bucket);
        }
        if (dimension.isEmpty()) activeOrigins.remove(party.origin().dimension());
    }

    private static int bucket(long coordinate) {
        return Math.toIntExact(Math.floorDiv(coordinate, INDEX_BUCKET_SIZE));
    }

    private record Bucket(int x, int z) {}
    private record Participation(double damage, long lastDamageTick) {}
    private static final class Tracking {
        private long createdAt;
        private long finishedAt;
        private final Map<UUID, Participation> participants = new LinkedHashMap<>();
        private Tracking(long createdAt, long finishedAt) {
            this.createdAt = createdAt;
            this.finishedAt = finishedAt;
        }
    }

    private static void requireTick(long tick) {
        if (tick < 0) throw new IllegalArgumentException("Encounter tick cannot be negative");
    }

    private static void requireDimension(String dimension) {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid encounter dimension");
        }
    }

    public static EncounterSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        EncounterNbt.require(tag, "schema_version", Tag.TAG_INT);
        int version = tag.getInt("schema_version");
        if (version != 1 && version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Living Kingdoms encounter schema: "
                    + tag.getInt("schema_version"));
        }
        ListTag entries = compoundList(tag, "parties");
        EncounterSavedData data = new EncounterSavedData();
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag record = entries.getCompound(i);
            HostileParty party = EncounterNbt.read(record);
            data.add(party);
            if (version >= 2) {
                EncounterNbt.require(record, "tracking", Tag.TAG_COMPOUND);
                data.tracking.put(party.id(), readTracking(record.getCompound("tracking"), party));
            }
        }
        if (version >= 2) {
            ListTag schedule = compoundList(tag, "natural_schedule");
            for (int i = 0; i < schedule.size(); i++) {
                CompoundTag entry = schedule.getCompound(i);
                EncounterNbt.require(entry, "dimension", Tag.TAG_STRING);
                EncounterNbt.require(entry, "next_tick", Tag.TAG_LONG);
                String dimension = entry.getString("dimension");
                long next = entry.getLong("next_tick");
                requireDimension(dimension);
                requireTick(next);
                if (data.naturalSchedule.putIfAbsent(dimension, next) != null) {
                    throw new IllegalArgumentException("Duplicate natural encounter dimension schedule");
                }
            }
        }
        data.setDirty(false);
        return data;
    }

    private static Tracking readTracking(CompoundTag tag, HostileParty party) {
        EncounterNbt.require(tag, "created_at", Tag.TAG_LONG);
        EncounterNbt.require(tag, "finished_at", Tag.TAG_LONG);
        long created = tag.getLong("created_at");
        long finished = tag.getLong("finished_at");
        if (created < -1 || finished < -1
                || (party.state() == PartyState.ALIVE && finished != -1)
                || (created >= 0 && finished >= 0 && finished < created)) {
            throw new IllegalArgumentException("Invalid encounter lifecycle timestamps");
        }
        Tracking entry = new Tracking(created, finished);
        ListTag participants = compoundList(tag, "participants");
        if (participants.size() > MAX_PARTICIPANTS) throw new IllegalArgumentException("Too many encounter participants");
        for (int i = 0; i < participants.size(); i++) {
            CompoundTag participant = participants.getCompound(i);
            if (!participant.hasUUID("player")) throw new IllegalArgumentException("Invalid encounter participant UUID");
            EncounterNbt.require(participant, "damage", Tag.TAG_DOUBLE);
            EncounterNbt.require(participant, "last_damage_tick", Tag.TAG_LONG);
            double damage = participant.getDouble("damage");
            long last = participant.getLong("last_damage_tick");
            if (!Double.isFinite(damage) || damage <= 0 || damage > MAX_DAMAGE || last < 0) {
                throw new IllegalArgumentException("Invalid encounter participation");
            }
            if (entry.participants.putIfAbsent(participant.getUUID("player"), new Participation(damage, last)) != null) {
                throw new IllegalArgumentException("Duplicate encounter participant UUID");
            }
        }
        return entry;
    }

    private static ListTag compoundList(CompoundTag tag, String field) {
        EncounterNbt.require(tag, field, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(field);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Encounter field must contain compounds: " + field);
        }
        return list;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag entries = new ListTag();
        parties.values().forEach(party -> {
            CompoundTag record = EncounterNbt.write(party);
            Tracking entry = tracking.get(party.id());
            CompoundTag lifecycle = new CompoundTag();
            lifecycle.putLong("created_at", entry.createdAt);
            lifecycle.putLong("finished_at", entry.finishedAt);
            ListTag participants = new ListTag();
            entry.participants.forEach((player, participation) -> {
                CompoundTag participant = new CompoundTag();
                participant.putUUID("player", player);
                participant.putDouble("damage", participation.damage);
                participant.putLong("last_damage_tick", participation.lastDamageTick);
                participants.add(participant);
            });
            lifecycle.put("participants", participants);
            record.put("tracking", lifecycle);
            entries.add(record);
        });
        tag.put("parties", entries);
        ListTag schedule = new ListTag();
        naturalSchedule.forEach((dimension, next) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("dimension", dimension);
            entry.putLong("next_tick", next);
            schedule.add(entry);
        });
        tag.put("natural_schedule", schedule);
        return tag;
    }
}

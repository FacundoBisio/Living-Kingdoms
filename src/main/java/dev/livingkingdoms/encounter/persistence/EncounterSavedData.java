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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Global server-owned metadata; member events resolve by UUID without entity/world scans. */
public final class EncounterSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_encounters";
    private static final int SCHEMA_VERSION = 1;
    private final Map<UUID, HostileParty> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberToParty = new HashMap<>();

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

    public Optional<HostileParty> get(UUID partyId) {
        return Optional.ofNullable(parties.get(Objects.requireNonNull(partyId, "partyId")));
    }

    /** Includes previously defeated members so duplicate death notifications remain harmless. */
    public Optional<HostileParty> forMember(UUID entityId) {
        UUID partyId = memberToParty.get(Objects.requireNonNull(entityId, "entityId"));
        return partyId == null ? Optional.empty() : Optional.of(parties.get(partyId));
    }

    public void add(HostileParty party) {
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
        setDirty();
    }

    /** Returns only the first full-party defeat, the point where gameplay may grant a receipt. */
    public Optional<HostileParty> recordDeath(UUID entityId) {
        HostileParty before = forMember(entityId).orElse(null);
        if (before == null) return Optional.empty();
        HostileParty after = before.withMemberDeath(entityId);
        if (after == before) return Optional.empty();
        parties.put(after.id(), after);
        setDirty();
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

    public static EncounterSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        EncounterNbt.require(tag, "schema_version", Tag.TAG_INT);
        if (tag.getInt("schema_version") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Living Kingdoms encounter schema: "
                    + tag.getInt("schema_version"));
        }
        EncounterNbt.require(tag, "parties", Tag.TAG_LIST);
        ListTag entries = (ListTag) tag.get("parties");
        if (!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Encounter entries must be compounds");
        }
        EncounterSavedData data = new EncounterSavedData();
        for (int i = 0; i < entries.size(); i++) data.add(EncounterNbt.read(entries.getCompound(i)));
        data.setDirty(false);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag entries = new ListTag();
        parties.values().forEach(party -> entries.add(EncounterNbt.write(party)));
        tag.put("parties", entries);
        return tag;
    }
}

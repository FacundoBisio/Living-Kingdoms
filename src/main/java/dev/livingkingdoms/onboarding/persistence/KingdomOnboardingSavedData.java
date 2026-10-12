package dev.livingkingdoms.onboarding.persistence;

import dev.livingkingdoms.onboarding.domain.KingdomName;
import dev.livingkingdoms.onboarding.domain.OnboardingStage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Event-driven onboarding receipts. Stores identifiers, never entities or tick schedules. */
public final class KingdomOnboardingSavedData extends SavedData {
    public static final String DATA_NAME = "livingkingdoms_onboarding";
    private static final int MAX_RECORDS = 100_000;
    private final Map<Key, OnboardingStage> journeys = new LinkedHashMap<>();
    private final Map<UUID, Registration> registrations = new LinkedHashMap<>();
    private final Map<String, UUID> names = new LinkedHashMap<>();

    public record Registration(UUID founder, String name, boolean legacy) {
        public Registration {
            Objects.requireNonNull(founder); Objects.requireNonNull(name);
            if (name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Invalid registered kingdom name");
            if (!legacy && KingdomName.validate(name).filter(name::equals).isEmpty())
                throw new IllegalArgumentException("Invalid new kingdom name");
        }
    }
    private record Key(UUID player, UUID settlement) {
        private Key { Objects.requireNonNull(player); Objects.requireNonNull(settlement); }
    }

    public static KingdomOnboardingSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Onboarding belongs to the server thread");
        var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(() -> {
            if (!Files.notExists(file)) throw new IllegalStateException("Existing kingdom onboarding data could not be loaded; refusing to overwrite it");
            return new KingdomOnboardingSavedData();
        }, KingdomOnboardingSavedData::load), DATA_NAME);
    }

    public Optional<OnboardingStage> journey(UUID player, UUID settlement) { return Optional.ofNullable(journeys.get(new Key(player, settlement))); }
    public OnboardingStage stage(UUID player, UUID settlement) { return journey(player, settlement).orElse(OnboardingStage.WELCOME); }
    public Optional<Registration> registration(UUID settlement) { return Optional.ofNullable(registrations.get(Objects.requireNonNull(settlement))); }
    public boolean nameAvailable(UUID settlement, String name) {
        UUID existing = names.get(KingdomName.key(name));
        return existing == null || existing.equals(settlement);
    }
    public boolean canRecord(UUID player, UUID settlement) {
        return (journeys.containsKey(new Key(player, settlement)) || journeys.size() < MAX_RECORDS)
                && (registrations.containsKey(settlement) || registrations.size() < MAX_RECORDS);
    }
    public boolean begin(UUID player, UUID settlement) {
        Key key = new Key(player, settlement);
        if (journeys.containsKey(key) || journeys.size() >= MAX_RECORDS) return false;
        journeys.put(key, OnboardingStage.WELCOME); setDirty(); return true;
    }
    public boolean advance(UUID player, UUID settlement, String action) {
        Key key = new Key(player, settlement);
        OnboardingStage stage = journeys.get(key);
        if (stage == null) return false;
        OnboardingStage next = stage.next(Objects.requireNonNull(action));
        if (next == stage) return false;
        journeys.put(key, next); setDirty(); return true;
    }
    /** A second collaborator cannot rename the shared settlement or claim another founding receipt. */
    public boolean join(UUID player, UUID settlement, String name) {
        if (stage(player, settlement) != OnboardingStage.NAMING || !canRecord(player, settlement)) return false;
        if (!registrations.containsKey(settlement)) {
            var valid = KingdomName.validate(name);
            if (valid.isEmpty() || !nameAvailable(settlement, valid.get())) return false;
            Registration registration = new Registration(player, valid.get(), false);
            registrations.put(settlement, registration); names.put(KingdomName.key(registration.name()), settlement);
        }
        journeys.put(new Key(player, settlement), OnboardingStage.JOINED); setDirty(); return true;
    }
    /** Existing accepted quests are grandfathered without repeating dialogue, renaming or granting rewards. */
    public boolean enrollLegacy(UUID player, UUID settlement, String existingName) {
        if (journey(player, settlement).isPresent() || !canRecord(player, settlement)) return false;
        if (!registrations.containsKey(settlement)) {
            registrations.put(settlement, new Registration(player, existingName, true));
            names.putIfAbsent(KingdomName.key(existingName), settlement);
        }
        journeys.put(new Key(player, settlement), OnboardingStage.JOINED); setDirty(); return true;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", 1);
        ListTag players = new ListTag();
        journeys.forEach((key, stage) -> { var entry = new CompoundTag(); entry.putUUID("player", key.player()); entry.putUUID("settlement", key.settlement()); entry.putString("stage", stage.name()); players.add(entry); });
        tag.put("journeys", players);
        ListTag kingdoms = new ListTag();
        registrations.forEach((id, registration) -> { var entry = new CompoundTag(); entry.putUUID("settlement", id); entry.putUUID("founder", registration.founder()); entry.putString("name", registration.name()); entry.putBoolean("legacy", registration.legacy()); kingdoms.add(entry); });
        tag.put("registrations", kingdoms); return tag;
    }
    public static KingdomOnboardingSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema_version", Tag.TAG_INT) || tag.getInt("schema_version") != 1) throw new IllegalArgumentException("Unsupported onboarding schema");
        KingdomOnboardingSavedData data = new KingdomOnboardingSavedData();
        ListTag kingdoms = entries(tag, "registrations");
        for (int i=0; i<kingdoms.size(); i++) {
            CompoundTag entry = kingdoms.getCompound(i);
            UUID id = uuid(entry, "settlement");
            if (!entry.contains("name", Tag.TAG_STRING) || !entry.contains("legacy", Tag.TAG_BYTE)
                    || entry.getByte("legacy") < 0 || entry.getByte("legacy") > 1) throw new IllegalArgumentException("Malformed kingdom registration");
            Registration registration = new Registration(uuid(entry, "founder"), entry.getString("name"), entry.getBoolean("legacy"));
            if (data.registrations.putIfAbsent(id, registration) != null) throw new IllegalArgumentException("Duplicate kingdom registration");
            UUID old = data.names.putIfAbsent(KingdomName.key(registration.name()), id);
            if (old != null && !registration.legacy() && !data.registrations.get(old).legacy()) throw new IllegalArgumentException("Duplicate kingdom name");
        }
        ListTag players = entries(tag, "journeys");
        for (int i=0; i<players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.contains("stage", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing onboarding stage");
            Key key = new Key(uuid(entry, "player"), uuid(entry, "settlement"));
            OnboardingStage stage = OnboardingStage.valueOf(entry.getString("stage"));
            if (stage == OnboardingStage.JOINED && !data.registrations.containsKey(key.settlement())) throw new IllegalArgumentException("Missing joined kingdom registration");
            if (data.journeys.putIfAbsent(key, stage) != null) throw new IllegalArgumentException("Duplicate onboarding journey");
        }
        return data;
    }
    private static ListTag entries(CompoundTag tag, String field) {
        if (!tag.contains(field, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing onboarding list: " + field);
        ListTag list = (ListTag) tag.get(field);
        if (list.size() > MAX_RECORDS || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid onboarding list: " + field);
        return list;
    }
    private static UUID uuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) throw new IllegalArgumentException("Invalid onboarding UUID: " + field);
        return tag.getUUID(field);
    }
}

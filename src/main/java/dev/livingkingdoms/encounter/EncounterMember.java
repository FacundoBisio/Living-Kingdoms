package dev.livingkingdoms.encounter;

import dev.livingkingdoms.faction.Faction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Small identity only; vanilla entity NBT retains position, health, equipment and AI. */
public record EncounterMember(UUID partyId, Faction faction) {
    private static final String DATA_KEY = "livingkingdoms:encounter";
    private static final int SCHEMA_VERSION = 1;

    public EncounterMember {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(faction, "faction");
        if (faction.isAllied()) throw new IllegalArgumentException("Hostile encounter members require a hostile faction");
    }

    public static Optional<EncounterMember> read(Entity entity) {
        CompoundTag persistent = entity.getPersistentData();
        if (!persistent.contains(DATA_KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag identity = persistent.getCompound(DATA_KEY);
        if (!identity.contains("schema_version", Tag.TAG_INT) || identity.getInt("schema_version") != SCHEMA_VERSION
                || !identity.hasUUID("party_id") || !identity.contains("faction", Tag.TAG_STRING)) return Optional.empty();
        try {
            return Optional.of(new EncounterMember(identity.getUUID("party_id"), Faction.fromId(identity.getString("faction"))));
        } catch (IllegalArgumentException malformedIdentity) {
            return Optional.empty();
        }
    }

    public static void attach(Entity entity, UUID partyId, Faction faction) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Encounter identity must be changed on the server thread");
        }
        EncounterMember requested = new EncounterMember(partyId, faction);
        if (entity.getPersistentData().contains(DATA_KEY)) {
            if (read(entity).filter(requested::equals).isPresent()) return;
            throw new IllegalStateException("Refusing to replace an existing Living Kingdoms encounter identity");
        }
        CompoundTag identity = new CompoundTag();
        identity.putInt("schema_version", SCHEMA_VERSION);
        identity.putUUID("party_id", partyId);
        identity.putString("faction", faction.id());
        entity.getPersistentData().put(DATA_KEY, identity);
    }
}

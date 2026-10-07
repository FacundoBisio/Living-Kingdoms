package dev.livingkingdoms.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-owned identity saved by NeoForge with the vanilla entity's persistent data. */
public record NpcIdentity(UUID settlementId, NpcRole role) {
    private static final String DATA_KEY = "livingkingdoms:npc";
    private static final int SCHEMA_VERSION = 1;

    public NpcIdentity {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(role, "role");
    }

    public static Optional<NpcIdentity> read(Entity entity) {
        CompoundTag persistent = entity.getPersistentData();
        if (!persistent.contains(DATA_KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag identity = persistent.getCompound(DATA_KEY);
        if (!identity.contains("schema_version", Tag.TAG_INT)
                || identity.getInt("schema_version") != SCHEMA_VERSION
                || !identity.hasUUID("settlement_id")
                || !identity.contains("role", Tag.TAG_STRING)) return Optional.empty();
        try {
            return Optional.of(new NpcIdentity(identity.getUUID("settlement_id"),
                    NpcRole.valueOf(identity.getString("role"))));
        } catch (IllegalArgumentException malformedIdentity) {
            return Optional.empty();
        }
    }

    public static void attach(Entity entity, UUID settlementId, NpcRole role) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            throw new IllegalStateException("Living Kingdoms NPC identities must be changed on the server thread");
        }
        NpcIdentity requested = new NpcIdentity(settlementId, role);
        if (entity.getPersistentData().contains(DATA_KEY)) {
            if (read(entity).filter(requested::equals).isPresent()) return;
            throw new IllegalStateException("Refusing to replace an existing Living Kingdoms NPC identity");
        }
        CompoundTag identity = new CompoundTag();
        identity.putInt("schema_version", SCHEMA_VERSION);
        identity.putUUID("settlement_id", settlementId);
        identity.putString("role", role.name());
        entity.getPersistentData().put(DATA_KEY, identity);
    }
}

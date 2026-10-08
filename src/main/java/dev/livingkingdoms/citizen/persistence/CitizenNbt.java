package dev.livingkingdoms.citizen.persistence;

import dev.livingkingdoms.citizen.domain.Citizen;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.domain.Housing;
import dev.livingkingdoms.citizen.domain.HousingStatus;
import dev.livingkingdoms.citizen.domain.ImmigrationCandidate;
import dev.livingkingdoms.progression.domain.LevelValue;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Strict serialization rejects corrupt identity/occupancy rather than silently replacing a valid world save. */
final class CitizenNbt {
    private CitizenNbt() {}

    static CompoundTag writeCitizen(Citizen citizen) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", citizen.id()); tag.putUUID("entity", citizen.entityId()); tag.putUUID("settlement", citizen.settlementId());
        tag.putString("name", citizen.name()); tag.putInt("level", citizen.level().value()); tag.putString("role", citizen.role().name());
        if (citizen.homeId() != null) tag.putUUID("home", citizen.homeId());
        tag.putString("state", citizen.state().name()); tag.putLong("joined_at", citizen.joinedAt());
        return tag;
    }

    static Citizen readCitizen(CompoundTag tag) {
        require(tag, "name", Tag.TAG_STRING); require(tag, "level", Tag.TAG_INT); require(tag, "role", Tag.TAG_STRING);
        require(tag, "state", Tag.TAG_STRING); require(tag, "joined_at", Tag.TAG_LONG);
        UUID home = tag.contains("home") ? uuid(tag, "home") : null;
        return new Citizen(uuid(tag, "id"), uuid(tag, "entity"), uuid(tag, "settlement"), tag.getString("name"),
                new LevelValue(tag.getInt("level")), CitizenRole.valueOf(tag.getString("role")), home,
                CitizenState.valueOf(tag.getString("state")), tag.getLong("joined_at"));
    }

    static CompoundTag writeHousing(Housing house) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", house.id()); tag.putUUID("settlement", house.settlementId());
        tag.putString("dimension", house.dimension()); tag.putString("template", house.template().toString());
        tag.put("position", writePosition(house.position())); tag.put("entrance", writePosition(house.entrance()));
        tag.putInt("capacity", house.capacity()); tag.putString("status", house.status().name());
        return tag;
    }

    static Housing readHousing(CompoundTag tag) {
        require(tag, "dimension", Tag.TAG_STRING); require(tag, "template", Tag.TAG_STRING);
        require(tag, "position", Tag.TAG_COMPOUND); require(tag, "entrance", Tag.TAG_COMPOUND);
        require(tag, "capacity", Tag.TAG_INT); require(tag, "status", Tag.TAG_STRING);
        return new Housing(uuid(tag, "id"), uuid(tag, "settlement"), tag.getString("dimension"),
                ResourceLocation.parse(tag.getString("template")), readPosition(tag.getCompound("position")),
                readPosition(tag.getCompound("entrance")), tag.getInt("capacity"), HousingStatus.valueOf(tag.getString("status")));
    }

    static CompoundTag writeCandidate(ImmigrationCandidate candidate) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", candidate.id()); tag.putUUID("settlement", candidate.settlementId());
        tag.putString("name", candidate.name()); tag.putInt("level", candidate.level().value());
        tag.putString("preferred_role", candidate.preferredRole().name());
        tag.putLong("created_at", candidate.createdAt()); tag.putLong("expires_at", candidate.expiresAt());
        return tag;
    }

    static ImmigrationCandidate readCandidate(CompoundTag tag) {
        require(tag, "name", Tag.TAG_STRING); require(tag, "level", Tag.TAG_INT); require(tag, "preferred_role", Tag.TAG_STRING);
        require(tag, "created_at", Tag.TAG_LONG); require(tag, "expires_at", Tag.TAG_LONG);
        return new ImmigrationCandidate(uuid(tag, "id"), uuid(tag, "settlement"), tag.getString("name"),
                new LevelValue(tag.getInt("level")), CitizenRole.valueOf(tag.getString("preferred_role")),
                tag.getLong("created_at"), tag.getLong("expires_at"));
    }

    private static CompoundTag writePosition(BlockPos pos) {
        CompoundTag tag = new CompoundTag(); tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ()); return tag;
    }

    private static BlockPos readPosition(CompoundTag tag) {
        require(tag, "x", Tag.TAG_INT); require(tag, "y", Tag.TAG_INT); require(tag, "z", Tag.TAG_INT);
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    static UUID uuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) throw new IllegalArgumentException("Invalid citizen UUID field: " + field);
        return tag.getUUID(field);
    }

    static void require(CompoundTag tag, String field, int type) {
        if (!tag.contains(field, type)) throw new IllegalArgumentException("Missing or invalid citizen field: " + field);
    }

    static ListTag compoundList(CompoundTag tag, String field) {
        require(tag, field, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(field);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Citizen list must contain compounds: " + field);
        return list;
    }
}

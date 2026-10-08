package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.domain.SettlementOrigin;
import dev.livingkingdoms.settlement.domain.SettlementProvenance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** NBT boundary; domain classes do not depend on Minecraft. */
final class SettlementNbt {
    private SettlementNbt() {}

    static CompoundTag write(Settlement settlement) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", settlement.id());
        tag.putString("name", settlement.name());
        tag.putString("faction", settlement.faction().id());
        tag.putInt("level", settlement.level());
        tag.putInt("population", settlement.population());
        CompoundTag provenance = new CompoundTag();
        provenance.putString("origin", settlement.provenance().origin().name());
        provenance.putLong("created_at_epoch_millis", settlement.provenance().createdAtEpochMillis());
        settlement.provenance().founder().ifPresent(id -> provenance.putUUID("founder", id));
        settlement.provenance().kingdom().ifPresent(id -> provenance.putUUID("kingdom", id));
        tag.put("provenance", provenance);
        Territory territory = settlement.territory();
        CompoundTag location = new CompoundTag();
        location.putString("dimension", territory.dimension());
        location.putInt("x", territory.x());
        location.putInt("y", territory.y());
        location.putInt("z", territory.z());
        location.putInt("radius", territory.radius());
        tag.put("territory", location);
        return tag;
    }

    static Settlement read(CompoundTag tag, boolean legacy) {
        if (!tag.hasUUID("id")) throw new IllegalArgumentException("Missing settlement UUID");
        require(tag, "name", Tag.TAG_STRING);
        require(tag, "faction", Tag.TAG_STRING);
        require(tag, "level", Tag.TAG_INT);
        require(tag, "population", Tag.TAG_INT);
        require(tag, "territory", Tag.TAG_COMPOUND);
        CompoundTag location = tag.getCompound("territory");
        require(location, "dimension", Tag.TAG_STRING);
        for (String field : new String[]{"x", "y", "z", "radius"}) {
            require(location, field, Tag.TAG_INT);
        }
        Territory territory = new Territory(location.getString("dimension"), location.getInt("x"),
                location.getInt("y"), location.getInt("z"), location.getInt("radius"));
        SettlementProvenance provenance = SettlementProvenance.legacy();
        if (!legacy) {
            require(tag, "provenance", Tag.TAG_COMPOUND);
            CompoundTag history = tag.getCompound("provenance");
            require(history, "origin", Tag.TAG_STRING);
            require(history, "created_at_epoch_millis", Tag.TAG_LONG);
            provenance = new SettlementProvenance(SettlementOrigin.valueOf(history.getString("origin")),
                    optionalUuid(history, "founder"), history.getLong("created_at_epoch_millis"), optionalUuid(history, "kingdom"));
        }
        return new Settlement(tag.getUUID("id"), tag.getString("name"),
                Faction.fromId(tag.getString("faction")), tag.getInt("level"),
                tag.getInt("population"), territory, provenance);
    }

    private static java.util.Optional<java.util.UUID> optionalUuid(CompoundTag tag, String key) {
        if (!tag.contains(key)) return java.util.Optional.empty();
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("Invalid provenance UUID: " + key);
        return java.util.Optional.of(tag.getUUID(key));
    }

    static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("Missing or invalid settlement field: " + key);
        }
    }
}

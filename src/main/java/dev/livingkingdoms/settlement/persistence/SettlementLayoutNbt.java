package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.structure.ArchitectureStyle;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.PlotBounds;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

/** Optional schema-1 extension. A missing layout means a legacy or metadata-only settlement. */
final class SettlementLayoutNbt {
    private SettlementLayoutNbt() {}

    static CompoundTag write(SettlementLayoutMetadata layout) {
        CompoundTag tag = new CompoundTag();
        tag.putString("style", layout.style().name());
        ListTag buildings = new ListTag();
        for (var building : layout.buildings()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("kind", building.kind().name());
            entry.putString("template", building.template().toString());
            entry.putString("rotation", building.rotation().name());
            entry.put("origin", position(building.origin()));
            entry.put("entrance", position(building.entrance()));
            entry.putInt("max_x", building.bounds().maxX());
            entry.putInt("max_z", building.bounds().maxZ());
            buildings.add(entry);
        }
        tag.put("buildings", buildings);
        tag.put("ports", positions(layout.ports()));
        tag.put("paths", positions(layout.paths()));
        return tag;
    }

    static SettlementLayoutMetadata read(CompoundTag tag) {
        SettlementNbt.require(tag, "style", Tag.TAG_STRING);
        List<SettlementLayoutMetadata.Building> buildings = new ArrayList<>();
        var entries = compounds(tag, "buildings", 256);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            for (String field : List.of("kind", "template", "rotation")) SettlementNbt.require(entry, field, Tag.TAG_STRING);
            for (String field : List.of("max_x", "max_z")) SettlementNbt.require(entry, field, Tag.TAG_INT);
            BlockPos origin = position(entry, "origin");
            buildings.add(new SettlementLayoutMetadata.Building(BuildingKind.valueOf(entry.getString("kind")),
                    ResourceLocation.parse(entry.getString("template")), origin, Rotation.valueOf(entry.getString("rotation")),
                    new PlotBounds(origin.getX(), origin.getZ(), entry.getInt("max_x"), entry.getInt("max_z")), position(entry, "entrance")));
        }
        return new SettlementLayoutMetadata(ArchitectureStyle.valueOf(tag.getString("style")), buildings,
                positions(tag, "ports", 16), positions(tag, "paths", 16384));
    }

    private static CompoundTag position(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
        return tag;
    }

    private static BlockPos position(CompoundTag parent, String key) {
        SettlementNbt.require(parent, key, Tag.TAG_COMPOUND);
        return position(parent.getCompound(key));
    }

    private static BlockPos position(CompoundTag tag) {
        for (String field : List.of("x", "y", "z")) SettlementNbt.require(tag, field, Tag.TAG_INT);
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    private static ListTag positions(List<BlockPos> positions) {
        ListTag tags = new ListTag(); positions.forEach(pos -> tags.add(position(pos))); return tags;
    }

    private static List<BlockPos> positions(CompoundTag tag, String key, int limit) {
        var entries = compounds(tag, key, limit);
        List<BlockPos> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) result.add(position(entries.getCompound(i)));
        return result;
    }

    private static ListTag compounds(CompoundTag tag, String key, int limit) {
        SettlementNbt.require(tag, key, Tag.TAG_LIST);
        ListTag entries = (ListTag) tag.get(key);
        if (entries.size() > limit || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid layout list: " + key);
        return entries;
    }
}

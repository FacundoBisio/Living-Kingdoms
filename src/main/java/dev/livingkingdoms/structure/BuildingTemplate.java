package dev.livingkingdoms.structure;

import dev.livingkingdoms.block.KingdomBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native NBT module contract; WorldEdit is an authoring tool only. */
public record BuildingTemplate(BuildingKind kind, ResourceLocation id, StructureTemplate template, Vec3i size,
                               List<StructureTemplate.StructureBlockInfo> blocks, BlockPos entrance) {
    public static BuildingTemplate validate(BuildingKind kind, ResourceLocation id, StructureTemplate template) {
        Vec3i size = template.getSize();
        if (size.getX() < 3 || size.getZ() < 3 || size.getX() > 15 || size.getZ() > 15
                || size.getY() < 3 || size.getY() > 12) throw new IllegalArgumentException("Unsafe module size: " + id);
        CompoundTag nbt = template.save(new CompoundTag());
        if (nbt.contains("palettes") || !nbt.getList("entities", 10).isEmpty())
            throw new IllegalArgumentException("Modules require one palette and no saved entities");
        List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
        Set<Block> types = new HashSet<>();
        var palette = nbt.getList("palette", 10);
        for (int i = 0; i < palette.size(); i++) {
            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(palette.getCompound(i).getString("Name")))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown module block"));
            if (block == Blocks.STRUCTURE_BLOCK || block == Blocks.STRUCTURE_VOID || block == Blocks.JIGSAW)
                throw new IllegalArgumentException("Remove authoring/control blocks before export");
            if (types.add(block)) blocks.addAll(template.filterBlocks(BlockPos.ZERO, SettlementTemplate.settings(), block));
        }
        Set<BlockPos> positions = new HashSet<>();
        int floors = 0;
        for (var info : blocks) {
            BlockPos pos = info.pos();
            if (pos.getX() < 0 || pos.getX() >= size.getX() || pos.getZ() < 0 || pos.getZ() >= size.getZ()
                    || pos.getY() < 0 || pos.getY() >= size.getY() || !positions.add(pos)
                    || !info.state().getFluidState().isEmpty()) throw new IllegalArgumentException("Invalid module block");
            if (pos.getY() == 0) {
                if (info.state().hasBlockEntity() || !Block.isShapeFullBlock(info.state().getCollisionShape(EmptyBlockGetter.INSTANCE, pos)))
                    throw new IllegalArgumentException("Modules need solid floors at Y=0");
                floors++;
            }
        }
        if (floors != size.getX() * size.getZ()) throw new IllegalArgumentException("Incomplete module floor");
        long markers = blocks.stream().filter(block -> block.state().is(Blocks.LODESTONE)).count();
        long boards = blocks.stream().filter(block -> block.state().is(KingdomBlocks.QUEST_BOARD)).count();
        if (kind == BuildingKind.CORE) {
            if ((size.getX() != 13 || size.getZ() != 13) || markers != 1 || boards != 1
                    || !blocks.stream().anyMatch(block -> block.pos().equals(new BlockPos(6, 1, 9)) && block.state().is(Blocks.LODESTONE)))
                throw new IllegalArgumentException("Core requires 13xHx13, marker (6,1,9), and one Quest Board");
        } else if (kind == BuildingKind.FOUNDING_CAMP) {
            if (size.getX() != 9 || size.getZ() != 9 || markers != 1 || boards != 1
                    || blocks.stream().noneMatch(block -> block.pos().equals(new BlockPos(4, 1, 4)) && block.state().is(Blocks.LODESTONE)))
                throw new IllegalArgumentException("Camp requires 9xHx9, marker (4,1,4), and one Quest Board");
        } else if (markers != 0 || boards != 0) throw new IllegalArgumentException("Only the core owns the marker and Quest Board");
        // South-facing export convention. The exterior doorstep stays outside the module footprint.
        BlockPos entrance = new BlockPos(size.getX() / 2, 0, size.getZ());
        if (kind != BuildingKind.CORE && kind != BuildingKind.FOUNDING_CAMP) {
            for (int y = 1; y <= 2; y++) {
                // Walls are inset one block inside the reserved eave/doorstep row.
                // Checking that outer row only checked exported air, allowing missing doors.
                BlockPos door = new BlockPos(size.getX() / 2, y, size.getZ() - 2);
                if (blocks.stream().noneMatch(block -> block.pos().equals(door)
                        && (kind == BuildingKind.WATCHTOWER ? block.state().isAir()
                        : block.state().is(net.minecraft.tags.BlockTags.WOODEN_DOORS))))
                    throw new IllegalArgumentException("Module must expose its south-center entrance");
            }
        }
        return new BuildingTemplate(kind, id, template, size, List.copyOf(blocks), entrance);
    }

    public Vec3i rotatedSize(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? new Vec3i(size.getZ(), size.getY(), size.getX()) : size;
    }

    public BlockPos nativeOrigin(BlockPos minimumCorner, Rotation rotation) {
        return template.getZeroPositionWithTransform(minimumCorner, Mirror.NONE, rotation);
    }

    public BlockPos worldPosition(BlockPos local, BlockPos minimumCorner, Rotation rotation) {
        return StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO).offset(nativeOrigin(minimumCorner, rotation));
    }

    public net.minecraft.world.level.levelgen.structure.BoundingBox worldBounds(BlockPos minimumCorner, Rotation rotation) {
        return template.getBoundingBox(SettlementTemplate.settings().setRotation(rotation), nativeOrigin(minimumCorner, rotation));
    }
}

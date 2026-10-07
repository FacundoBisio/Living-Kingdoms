package dev.livingkingdoms.structure;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.block.KingdomBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads native datapack templates and validates the small, surface-settlement contract. */
public record SettlementTemplate(StructureTemplate template, Vec3i size, BlockPos marker,
                                 List<StructureTemplate.StructureBlockInfo> blocks) {
    public static final ResourceLocation TEST_SETTLEMENT =
            ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID, "allied/test_settlement");
    private static final int MAX_SIDE = 32;
    private static final int MAX_HEIGHT = 12;

    public static SettlementTemplate load(ServerLevel level) {
        StructureTemplate template = level.getStructureManager().get(TEST_SETTLEMENT)
                .orElseThrow(() -> new IllegalArgumentException("Missing template: " + TEST_SETTLEMENT));
        return validate(template);
    }

    public static SettlementTemplate validate(StructureTemplate template) {
        Vec3i size = template.getSize();
        if (size.getX() < 3 || size.getX() > MAX_SIDE || size.getZ() < 3 || size.getZ() > MAX_SIDE
                || size.getY() < 2 || size.getY() > MAX_HEIGHT) {
            throw new IllegalArgumentException("Settlement template exceeds supported bounds");
        }
        CompoundTag nbt = template.save(new CompoundTag());
        if (nbt.contains("palettes") || !nbt.getList("entities", 10).isEmpty()) {
            throw new IllegalArgumentException("Settlement templates require one palette and no entities");
        }
        ListTag palette = nbt.getList("palette", 10);
        Set<Block> seenBlocks = new HashSet<>();
        List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(palette.getCompound(i).getString("Name")))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown template block"));
            if (block == Blocks.STRUCTURE_BLOCK || block == Blocks.STRUCTURE_VOID || block == Blocks.JIGSAW) {
                throw new IllegalArgumentException("Export template without editor/control blocks");
            }
            if (seenBlocks.add(block)) blocks.addAll(template.filterBlocks(BlockPos.ZERO, settings(), block));
        }
        Set<BlockPos> occupied = new HashSet<>();
        int floors = 0;
        for (var info : blocks) {
            BlockPos pos = info.pos();
            if (pos.getX() < 0 || pos.getX() >= size.getX() || pos.getY() < 0 || pos.getY() >= size.getY()
                    || pos.getZ() < 0 || pos.getZ() >= size.getZ() || !occupied.add(pos)) {
                throw new IllegalArgumentException("Invalid or duplicate template position");
            }
            if (!info.state().getFluidState().isEmpty()) throw new IllegalArgumentException("Liquid in settlement template");
            if (pos.getY() == 0) {
                if (info.state().hasBlockEntity() || !Block.isShapeFullBlock(
                        info.state().getCollisionShape(EmptyBlockGetter.INSTANCE, pos))) {
                    throw new IllegalArgumentException("Template requires a solid foundation at local Y=0");
                }
                floors++;
            }
        }
        if (floors != size.getX() * size.getZ()) throw new IllegalArgumentException("Incomplete template foundation");
        List<BlockPos> markers = blocks.stream().filter(info -> info.state().is(Blocks.LODESTONE))
                .map(StructureTemplate.StructureBlockInfo::pos).toList();
        long boards = blocks.stream().filter(info -> info.state().is(KingdomBlocks.QUEST_BOARD)).count();
        if (markers.size() != 1 || markers.getFirst().getY() != 1 || boards != 1) {
            throw new IllegalArgumentException("Template requires one lodestone marker at Y=1 and one Quest Board");
        }
        return new SettlementTemplate(template, size, markers.getFirst(), List.copyOf(blocks));
    }

    public int minimumTerritoryRadius() {
        int dx = Math.max(marker.getX(), size.getX() - 1 - marker.getX());
        int dz = Math.max(marker.getZ(), size.getZ() - 1 - marker.getZ());
        return (int) Math.ceil(Math.hypot(dx, dz)) + 4;
    }

    public static StructurePlaceSettings settings() {
        return new StructurePlaceSettings().setIgnoreEntities(true).setKnownShape(true)
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
    }
}

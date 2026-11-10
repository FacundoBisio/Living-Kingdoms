package dev.livingkingdoms.economy;

import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Map;

/**
 * Upgrades show on the building itself. Level 2 refines the materials (cobblestone becomes stone bricks, oak
 * becomes spruce); level 3 turns it into a stately building (deepslate brick walls, dark oak timber and roof,
 * kingdom banners by the door). Only blocks of the building's own footprint and only these exact materials are
 * swapped, keeping every stair/slab/fence orientation; containers, beds, doors and desks are never touched.
 */
public final class BuildingVisuals {
    private static final Map<Block, Block> LEVEL_2 = Map.ofEntries(
            Map.entry(Blocks.COBBLESTONE, Blocks.STONE_BRICKS), Map.entry(Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_STONE_BRICKS),
            Map.entry(Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICK_STAIRS), Map.entry(Blocks.COBBLESTONE_SLAB, Blocks.STONE_BRICK_SLAB),
            Map.entry(Blocks.COBBLESTONE_WALL, Blocks.STONE_BRICK_WALL), Map.entry(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS),
            Map.entry(Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS), Map.entry(Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB),
            Map.entry(Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE), Map.entry(Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG),
            Map.entry(Blocks.OAK_LOG, Blocks.SPRUCE_LOG), Map.entry(Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS_PANE));
    private static final Map<Block, Block> LEVEL_3 = Map.ofEntries(
            Map.entry(Blocks.STONE_BRICKS, Blocks.DEEPSLATE_BRICKS), Map.entry(Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS),
            Map.entry(Blocks.STONE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_STAIRS), Map.entry(Blocks.STONE_BRICK_SLAB, Blocks.DEEPSLATE_BRICK_SLAB),
            Map.entry(Blocks.STONE_BRICK_WALL, Blocks.DEEPSLATE_BRICK_WALL), Map.entry(Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS),
            Map.entry(Blocks.SPRUCE_STAIRS, Blocks.DARK_OAK_STAIRS), Map.entry(Blocks.SPRUCE_SLAB, Blocks.DARK_OAK_SLAB),
            Map.entry(Blocks.SPRUCE_FENCE, Blocks.DARK_OAK_FENCE), Map.entry(Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_DARK_OAK_LOG),
            Map.entry(Blocks.SPRUCE_LOG, Blocks.DARK_OAK_LOG));
    private BuildingVisuals() {}

    public static Map<Block, Block> mapping(int level) { return level >= 3 ? LEVEL_3 : level == 2 ? LEVEL_2 : Map.of(); }

    /** Applies every visual tier up to {@code level}; returns the number of blocks changed. Idempotent. */
    public static int apply(ServerLevel level, FunctionalBuilding b, int buildingLevel) {
        if (b.template().getPath().contains("converted")) return 0;
        var box = b.bounds(); int y0 = b.origin().getY(), changed = 0;
        if (!level.hasChunkAt(new BlockPos(box.minX(), y0, box.minZ())) || !level.hasChunkAt(new BlockPos(box.maxX(), y0, box.maxZ()))) return 0;
        for (int tier = 2; tier <= Math.min(3, buildingLevel); tier++) {
            var map = mapping(tier);
            for (int y = y0; y <= y0 + 13; y++) for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) {
                var pos = new BlockPos(x, y, z); var state = level.getBlockState(pos);
                var target = map.get(state.getBlock());
                if (target == null || level.getBlockEntity(pos) != null) continue;
                if (level.setBlock(pos, target.withPropertiesOf(state), Block.UPDATE_CLIENTS)) changed++;
            }
        }
        if (buildingLevel >= 3) changed += banners(level, b);
        if (changed > 0) {
            var c = b.entrance().above(2);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.getX() + .5, c.getY(), c.getZ() + .5, 30, 2, 2, 2, 0);
            level.playSound(null, c, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, .8F, 1.1F);
        }
        return changed;
    }

    private static int banners(ServerLevel level, FunctionalBuilding b) {
        var box = b.bounds(); var door = b.entrance();
        double cx = (box.minX() + box.maxX()) / 2.0, cz = (box.minZ() + box.maxZ()) / 2.0;
        var out = Direction.getNearest(door.getX() - cx, 0, door.getZ() - cz);
        int rotation = switch (out) { case SOUTH -> 0; case WEST -> 4; case NORTH -> 8; default -> 12; };
        int placed = 0;
        for (int side : new int[]{2, -2}) {
            var base = door.relative(out.getClockWise(), side);
            for (int dy = 1; dy >= -1; dy--) {
                var at = base.above(dy);
                if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.above(2)).isAir()
                        && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) {
                    level.setBlock(at, Blocks.DARK_OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(at.above(), Blocks.BLUE_BANNER.defaultBlockState().setValue(BannerBlock.ROTATION, rotation), Block.UPDATE_ALL);
                    placed++; break;
                }
            }
        }
        return placed;
    }
}

package dev.livingkingdoms.rpg;

import dev.livingkingdoms.rpg.domain.TownDecor;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/**
 * Makes a kingdom visibly grow with its rank: lanterns along paths, flower beds, banners, a fountain, benches,
 * braziers, hedges and finally a hero statue. Every write targets an empty, supported, loaded position inside
 * the territory, outside building footprints and off the paths themselves; tiers are applied once each.
 */
public final class TownDecorService {
    private static final int FLAGS = Block.UPDATE_ALL;
    private TownDecorService() {}

    /** Applies every unlocked tier not yet applied. Safe to call repeatedly (e.g. on rank-up or by command). */
    public static List<TownDecor> beautify(ServerLevel level, UUID settlementId, int rank) {
        var settlement = SettlementSavedData.get(level.getServer()).get(settlementId).orElse(null);
        if (settlement == null || !settlement.faction().isAllied()
                || !settlement.territory().dimension().equals(level.dimension().location().toString())) return List.of();
        var data = RpgSavedData.get(level.getServer());
        var site = Site.of(level, settlement);
        var applied = new ArrayList<TownDecor>();
        for (var tier : TownDecor.pending(rank, data.decor(settlementId))) {
            int placed = switch (tier) {
                case LANTERNS -> lanterns(level, site);
                case FLOWERS -> flowers(level, site);
                case BANNERS -> banners(level, site);
                case FOUNTAIN -> fountain(level, site);
                case BENCHES -> benches(level, site);
                case BRAZIERS -> braziers(level, site);
                case HEDGES -> hedges(level, site);
                case STATUE -> statue(level, site, settlement);
            };
            // A tier counts as applied even when the terrain allowed few pieces, so it never re-floods later.
            data.markDecor(settlementId, tier.bit());
            if (placed > 0) applied.add(tier);
        }
        if (!applied.isEmpty()) {
            var center = site.center;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.getX() + .5, center.getY() + 2, center.getZ() + .5, 40, 4, 1.5, 4, 0);
            level.playSound(null, center, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.2F, 1F);
            for (var player : level.players())
                if (settlement.territory().contains(settlement.territory().dimension(), player.getBlockX(), player.getBlockZ()))
                    for (var tier : applied) player.sendSystemMessage(Component.translatable("decor.livingkingdoms.applied",
                            VillageNames.display(settlement), Component.translatable("decor.livingkingdoms.tier." + tier.id())).withStyle(ChatFormatting.GREEN));
        }
        return applied;
    }

    /** Snapshot of where decorations may go: path tiles, building footprints and the plaza centre. */
    record Site(BlockPos center, List<BlockPos> paths, List<net.minecraft.world.level.levelgen.structure.BoundingBox> buildings, Settlement settlement) {
        static Site of(ServerLevel level, Settlement settlement) {
            var t = settlement.territory();
            var center = new BlockPos(t.x(), t.y(), t.z());
            var layout = SettlementSavedData.get(level.getServer()).layout(settlement.id()).orElse(null);
            var paths = new ArrayList<BlockPos>();
            var buildings = new ArrayList<net.minecraft.world.level.levelgen.structure.BoundingBox>();
            if (layout != null) {
                paths.addAll(layout.paths());
                for (var b : layout.buildings()) buildings.add(new net.minecraft.world.level.levelgen.structure.BoundingBox(
                        b.bounds().minX() - 1, b.origin().getY() - 2, b.bounds().minZ() - 1, b.bounds().maxX() + 1, b.origin().getY() + 24, b.bounds().maxZ() + 1));
            }
            if (paths.isEmpty()) {
                // Converted villages: use their own dirt paths near the plaza (bounded surface scan).
                for (int dx = -28; dx <= 28; dx++) for (int dz = -28; dz <= 28; dz++) {
                    var column = center.offset(dx, 0, dz);
                    if (!level.hasChunkAt(column)) continue;
                    var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).below();
                    if (level.getBlockState(top).is(Blocks.DIRT_PATH)) paths.add(top);
                    if (paths.size() >= 400) break;
                }
            }
            paths.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
            return new Site(center, List.copyOf(paths), List.copyOf(buildings), settlement);
        }
        boolean free(BlockPos pos) {
            var t = settlement.territory();
            if (!t.contains(t.dimension(), pos.getX(), pos.getZ())) return false;
            for (var b : buildings) if (b.isInside(pos)) return false;
            return !pathColumn(pos);
        }
        boolean pathColumn(BlockPos pos) {
            for (var p : paths) if (p.getX() == pos.getX() && p.getZ() == pos.getZ() && Math.abs(p.getY() - pos.getY()) <= 3) return true;
            return false;
        }
    }

    private static boolean empty(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        var state = level.getBlockState(pos);
        return (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) && level.getBlockEntity(pos) == null;
    }

    /** Surface standing spot (air at feet and head, solid floor) next to a path tile, or null. */
    private static BlockPos beside(ServerLevel level, Site site, BlockPos path, int distance) {
        for (var dir : Direction.Plane.HORIZONTAL) {
            var feet = path.relative(dir, distance).above();
            for (int dy = 1; dy >= -1; dy--) {
                var at = feet.above(dy);
                if (site.free(at) && empty(level, at) && empty(level, at.above()) && level.getBlockState(at.below()).isSolidRender(level, at.below()))
                    return at;
            }
        }
        return null;
    }

    private static boolean set(ServerLevel level, BlockPos pos, BlockState state) { return level.setBlock(pos, state, FLAGS); }

    private static int lanterns(ServerLevel level, Site site) {
        int placed = 0;
        for (int i = 0; i < site.paths.size() && placed < 16; i += 6) {
            var at = beside(level, site, site.paths.get(i), 1);
            if (at == null || !empty(level, at.above(2))) continue;
            if (set(level, at, Blocks.SPRUCE_FENCE.defaultBlockState()) && set(level, at.above(), Blocks.LANTERN.defaultBlockState())) placed++;
        }
        return placed;
    }

    private static final Block[] FLOWERS = {Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.LILY_OF_THE_VALLEY};
    private static int flowers(ServerLevel level, Site site) {
        int placed = 0; var random = level.getRandom();
        for (int i = 0; i < site.paths.size() && placed < 36; i += 2) {
            var at = beside(level, site, site.paths.get(i), 2);
            if (at == null || !level.getBlockState(at.below()).is(BlockTags.DIRT)) continue;
            if (set(level, at, FLOWERS[random.nextInt(FLOWERS.length)].defaultBlockState())) placed++;
        }
        return placed;
    }

    /** Ground-level spot near the plaza at the given offset, adjusted to the surface. */
    private static BlockPos ground(ServerLevel level, Site site, int dx, int dz) {
        var column = site.center.offset(dx, 0, dz);
        if (!level.hasChunkAt(column)) return null;
        var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (Math.abs(top.getY() - site.center.getY()) > 4 || !site.free(top) || !empty(level, top) || !empty(level, top.above())
                || !level.getBlockState(top.below()).isSolidRender(level, top.below())) return null;
        return top;
    }

    private static int banners(ServerLevel level, Site site) {
        int placed = 0;
        int[][] corners = {{6, 6}, {-6, 6}, {6, -6}, {-6, -6}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}};
        for (var c : corners) {
            if (placed >= 4) break;
            var at = ground(level, site, c[0], c[1]);
            if (at == null || !empty(level, at.above(2))) continue;
            int rotation = Math.floorMod(Math.round((float) (Math.atan2(-c[0], c[1]) * 8 / Math.PI)), 16);
            if (set(level, at, Blocks.SPRUCE_FENCE.defaultBlockState())
                    && set(level, at.above(), Blocks.BLUE_BANNER.defaultBlockState().setValue(BannerBlock.ROTATION, rotation))) placed++;
        }
        return placed;
    }

    private static int fountain(ServerLevel level, Site site) {
        for (int ring = 6; ring <= 14; ring += 2) for (int[] d : new int[][]{{ring, 0}, {-ring, 0}, {0, ring}, {0, -ring}, {ring, ring}, {-ring, -ring}}) {
            var base = ground(level, site, d[0], d[1]);
            if (base == null || !flat5x5(level, site, base)) continue;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                var pos = base.offset(x, 0, z);
                boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
                set(level, pos, rim ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
            set(level, base, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            set(level, base.above(), Blocks.STONE_BRICK_WALL.defaultBlockState());
            set(level, base.above(2), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            set(level, base.above(3), Blocks.WATER.defaultBlockState());
            return 1;
        }
        return 0;
    }

    private static boolean flat5x5(ServerLevel level, Site site, BlockPos base) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            var pos = base.offset(x, 0, z);
            if (!site.free(pos) || !empty(level, pos) || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) return false;
            for (int y = 1; y <= 4; y++) if (!empty(level, pos.above(y))) return false;
        }
        return true;
    }

    private static int benches(ServerLevel level, Site site) {
        int placed = 0;
        for (int i = 3; i < site.paths.size() && placed < 6; i += 9) {
            var path = site.paths.get(i);
            for (var dir : Direction.Plane.HORIZONTAL) {
                var a = path.relative(dir, 2).above(); var side = dir.getClockWise(); var b = a.relative(side);
                if (!site.free(a) || !site.free(b) || !empty(level, a) || !empty(level, b) || !empty(level, a.above()) || !empty(level, b.above())
                        || !level.getBlockState(a.below()).isSolidRender(level, a.below()) || !level.getBlockState(b.below()).isSolidRender(level, b.below())) continue;
                var seat = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dir).setValue(StairBlock.HALF, Half.BOTTOM);
                set(level, a, seat); set(level, b, seat); placed++; break;
            }
        }
        return placed;
    }

    private static int braziers(ServerLevel level, Site site) {
        int placed = 0;
        for (var c : new int[][]{{5, 5}, {-5, 5}, {5, -5}, {-5, -5}, {9, 3}, {-9, -3}}) {
            if (placed >= 4) break;
            var at = ground(level, site, c[0], c[1]);
            if (at == null || !empty(level, at.above(2))) continue;
            if (set(level, at, Blocks.STONE_BRICKS.defaultBlockState()) && set(level, at.above(), Blocks.CAMPFIRE.defaultBlockState())) placed++;
        }
        return placed;
    }

    private static int hedges(ServerLevel level, Site site) {
        int placed = 0; var random = level.getRandom();
        for (int i = 1; i < site.paths.size() && placed < 14; i += 5) {
            var at = beside(level, site, site.paths.get(i), 2);
            if (at == null) continue;
            var leaves = (random.nextBoolean() ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES).defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            if (set(level, at, leaves)) placed++;
        }
        return placed;
    }

    private static int statue(ServerLevel level, Site site, Settlement settlement) {
        for (var c : new int[][]{{0, 5}, {5, 0}, {0, -5}, {-5, 0}, {7, 7}, {-7, -7}}) {
            var at = ground(level, site, c[0], c[1]);
            if (at == null || !empty(level, at.above(2)) || !empty(level, at.above(3))) continue;
            set(level, at, Blocks.GOLD_BLOCK.defaultBlockState());
            var stand = EntityType.ARMOR_STAND.create(level);
            if (stand == null) return 0;
            float yaw = (float) Math.toDegrees(Math.atan2(c[0], -c[1]));
            stand.moveTo(at.getX() + .5, at.getY() + 1, at.getZ() + .5, yaw, 0);
            stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
            stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
            stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.GOLDEN_LEGGINGS));
            stand.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.GOLDEN_BOOTS));
            stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
            stand.setCustomName(Component.translatable("decor.livingkingdoms.statue_name", VillageNames.display(settlement)).withStyle(ChatFormatting.GOLD));
            stand.setCustomNameVisible(true);
            stand.setInvulnerable(true);
            level.addFreshEntity(stand);
            return 1;
        }
        return 0;
    }
}

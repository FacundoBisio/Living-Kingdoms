package dev.livingkingdoms.rpg;

import dev.livingkingdoms.rpg.domain.WallGeometry;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.structure.PlotBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;

/**
 * Read-only wall footprint queries for systems that write near the ring (plot planner, construction). A settlement
 * has a wall when its fortification has a tier or is being raised. Reserved cells are the footprint of any design
 * (ring, towers, gatehouses) plus a one-block margin, and a 3-wide corridor 4 blocks deep on both sides of each gate.
 */
public final class WallFootprint {
    public static final int MARGIN = 1, CORRIDOR_DEPTH = 4, CORRIDOR_HALF_WIDTH = 1;
    /** Every reserved cell lies within this distance of the ring radius (towers +2, corridor +4 and lateral). */
    private static final int BAND = 6;
    private WallFootprint() {}

    private record Wall(WallGeometry geo, int radius) {}

    private static Wall wall(MinecraftServer server, Settlement settlement) {
        var data = RpgSavedData.get(server);
        var wall = data.wall(settlement.id()).orElse(null);
        if (wall == null || wall.tier() == 0 && wall.complete()) return null;
        return new Wall(WallGeometry.of(wall.radius(), data.design(settlement.id())), wall.radius());
    }

    /** Whether new buildings must keep off this column: wall footprint ±1 or a main gate corridor. */
    public static boolean reserved(MinecraftServer server, Settlement settlement, int x, int z) {
        var wall = wall(server, settlement);
        return wall != null && reserved(wall, x - settlement.territory().x(), z - settlement.territory().z());
    }

    /** Whether any column of the plot is reserved. Resolves the wall once; skips plots far from the ring. */
    public static boolean reserved(MinecraftServer server, Settlement settlement, PlotBounds bounds) {
        var wall = wall(server, settlement);
        if (wall == null) return false;
        int cx = settlement.territory().x(), cz = settlement.territory().z();
        long nearX = Math.max(0, Math.max(bounds.minX() - cx, cx - bounds.maxX())), nearZ = Math.max(0, Math.max(bounds.minZ() - cz, cz - bounds.maxZ()));
        long farX = Math.max(Math.abs(bounds.minX() - cx), Math.abs(bounds.maxX() - cx)), farZ = Math.max(Math.abs(bounds.minZ() - cz), Math.abs(bounds.maxZ() - cz));
        long outer = wall.radius() + BAND, inner = Math.max(0, wall.radius() - BAND);
        if (nearX * nearX + nearZ * nearZ > outer * outer || farX * farX + farZ * farZ < inner * inner) return false;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++)
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) if (reserved(wall, x - cx, z - cz)) return true;
        return false;
    }

    /** Whether the block is part of the wall: on the footprint AND wall material or a gate door. */
    public static boolean owned(ServerLevel level, Settlement settlement, BlockPos pos) {
        var wall = wall(level.getServer(), settlement);
        if (wall == null || !wall.geo().ownsAnyDesign(pos.getX() - settlement.territory().x(), pos.getZ() - settlement.territory().z())) return false;
        var state = level.getBlockState(pos);
        return WallService.wallBlock(state) || state.getBlock() instanceof DoorBlock;
    }

    private static boolean reserved(Wall wall, int dx, int dz) {
        long d2 = (long) dx * dx + (long) dz * dz, outer = wall.radius() + BAND, inner = Math.max(0, wall.radius() - BAND);
        if (d2 > outer * outer || d2 < inner * inner) return false;
        var geo = wall.geo();
        for (int ex = -MARGIN; ex <= MARGIN; ex++) for (int ez = -MARGIN; ez <= MARGIN; ez++) if (geo.ownsAnyDesign(dx + ex, dz + ez)) return true;
        for (int g : geo.gateCenters()) {
            int[] o = geo.ring().get(g);
            double length = Math.hypot(o[0], o[1]), ux = o[0] / length, uz = o[1] / length;
            double along = (dx - o[0]) * ux + (dz - o[1]) * uz, across = (dx - o[0]) * uz - (dz - o[1]) * ux;
            if (Math.abs(along) <= CORRIDOR_DEPTH + .5 && Math.abs(across) <= CORRIDOR_HALF_WIDTH + .5) return true;
        }
        return false;
    }
}

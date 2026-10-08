package dev.livingkingdoms.settlement;

import dev.livingkingdoms.config.SettlementEstablishmentConfig;
import dev.livingkingdoms.npc.NpcIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Event-only survey of loaded entities and HOME/MEETING POIs, rechecked against actual blocks. */
public record VillageSurvey(BlockPos center, List<BlockPos> beds, List<BlockPos> bells,
                            List<Villager> villagers, boolean fullyLoaded) {
    public VillageSurvey { beds = List.copyOf(beds); bells = List.copyOf(bells); villagers = List.copyOf(villagers); }
    public boolean hasSignals() { return !beds.isEmpty() || !bells.isEmpty() || !villagers.isEmpty(); }
    public boolean valid() {
        return fullyLoaded && villagers.size() >= SettlementEstablishmentConfig.MIN_VILLAGERS.get()
                && beds.size() >= SettlementEstablishmentConfig.MIN_BEDS.get();
    }

    public static VillageSurvey detect(ServerLevel level, BlockPos attempt) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Village surveys require the server thread");
        VillageSurvey nearby = scan(level, attempt);
        if (!nearby.fullyLoaded() || !nearby.hasSignals()) return nearby;
        BlockPos center = nearby.bells().stream().min(Comparator.comparingDouble(pos -> pos.distSqr(attempt)))
                .orElseGet(() -> nearby.beds().isEmpty() ? attempt : centroid(nearby.beds()));
        return center.equals(attempt) ? nearby : scan(level, center);
    }

    private static BlockPos centroid(List<BlockPos> positions) {
        return new BlockPos((int)positions.stream().mapToLong(BlockPos::getX).average().orElseThrow(),
                (int)positions.stream().mapToLong(BlockPos::getY).average().orElseThrow(),
                (int)positions.stream().mapToLong(BlockPos::getZ).average().orElseThrow());
    }

    private static VillageSurvey scan(ServerLevel level, BlockPos center) {
        int radius = SettlementEstablishmentConfig.DETECTION_RADIUS.get();
        var beds = new ArrayList<BlockPos>();
        var bells = new ArrayList<BlockPos>();
        boolean loaded = true;
        for (int x = (center.getX()-radius)>>4; x <= (center.getX()+radius)>>4; x++) {
            for (int z = (center.getZ()-radius)>>4; z <= (center.getZ()+radius)>>4; z++) {
                // PoiManager's broad range query reads unloaded POI files. Restrict the query to loaded chunks instead.
                if (!level.getChunkSource().hasChunk(x,z)) { loaded = false; continue; }
                level.getPoiManager().getInChunk(type -> type.is(PoiTypes.HOME) || type.is(PoiTypes.MEETING),
                        new ChunkPos(x,z), PoiManager.Occupancy.ANY).forEach(record -> {
                    BlockPos pos = record.getPos();
                    if (!inRange(pos, center, radius)) return;
                    if (record.getPoiType().is(PoiTypes.HOME) && completeBed(level,pos)) beds.add(pos.immutable());
                    if (record.getPoiType().is(PoiTypes.MEETING) && level.getBlockState(pos).is(Blocks.BELL)) bells.add(pos.immutable());
                });
            }
        }
        var villagers = level.getEntitiesOfClass(Villager.class, new AABB(center).inflate(radius,24,radius),
                villager -> villager.isAlive() && NpcIdentity.isUnassigned(villager)
                        && inRange(villager.blockPosition(),center,radius)).stream()
                .sorted(Comparator.comparing(Villager::getUUID)).toList();
        return new VillageSurvey(center,beds,bells,villagers,loaded);
    }

    private static boolean inRange(BlockPos pos, BlockPos center, int radius) {
        long dx = (long)pos.getX()-center.getX(), dz = (long)pos.getZ()-center.getZ();
        return Math.abs(pos.getY()-center.getY()) <= 24 && dx*dx+dz*dz <= (long)radius*radius;
    }

    private static boolean completeBed(ServerLevel level, BlockPos head) {
        var state = level.getBlockState(head);
        if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        BlockPos foot = head.relative(state.getValue(BedBlock.FACING).getOpposite());
        if (!level.getChunkSource().hasChunk(foot.getX()>>4,foot.getZ()>>4)) return false;
        var other = level.getBlockState(foot);
        return other.is(state.getBlock()) && other.getValue(BedBlock.PART) == BedPart.FOOT
                && other.getValue(BedBlock.FACING) == state.getValue(BedBlock.FACING);
    }
}

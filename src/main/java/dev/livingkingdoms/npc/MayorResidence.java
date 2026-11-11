package dev.livingkingdoms.npc;

import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Once the Town Hall stands, the Mayor walks from the camp into it and stays there. Checked every five seconds
 * only for kingdoms with a player inside; the walk is transient (a restart simply starts it again) and falls back
 * to a short hop if no path is found within 40 seconds. The Mayor is frozen (NoAI) again on arrival.
 */
public final class MayorResidence {
    private static final Map<UUID, Long> WALKING = new HashMap<>();
    private static final int TIMEOUT = 800;
    private MayorResidence() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer(); long now = server.overworld().getGameTime();
        if (now % 20 != 3) return;
        boolean scan = now % 100 == 3;
        for (var player : server.getPlayerList().getPlayers()) {
            if (!scan && WALKING.isEmpty()) break;
            var level = player.serverLevel();
            var settlement = SettlementSavedData.get(server).alliedAt(level.dimension().location().toString(), player.getBlockX(), player.getBlockZ()).orElse(null);
            if (settlement == null) continue;
            var mayorId = QuestSavedData.get(server).mayor(settlement.id()).orElse(null);
            if (mayorId == null || !(level.getEntity(mayorId) instanceof Villager mayor) || !mayor.isAlive()) continue;
            var seat = seat(level, settlement.id());
            if (seat == null) continue;
            double distance = mayor.distanceToSqr(seat.getX() + .5, seat.getY(), seat.getZ() + .5);
            Long started = WALKING.get(mayorId);
            if (distance <= 2.25) {
                if (started != null || !mayor.isNoAi()) { mayor.getNavigation().stop(); mayor.setNoAi(true); WALKING.remove(mayorId); face(level, settlement.id(), mayor); }
                continue;
            }
            if (started == null) {
                if (!scan || mayor.isTrading() || WALKING.size() >= 64) continue;
                WALKING.put(mayorId, now);
                mayor.setNoAi(false);
                mayor.getBrain().stopAll(level, mayor); mayor.getBrain().removeAllBehaviors();
            } else if (now - started > TIMEOUT) {
                mayor.teleportTo(seat.getX() + .5, seat.getY(), seat.getZ() + .5);
                mayor.setNoAi(true); WALKING.remove(mayorId); face(level, settlement.id(), mayor);
                continue;
            }
            mayor.getNavigation().moveTo(seat.getX() + .5, seat.getY(), seat.getZ() + .5, .6);
        }
    }

    /** Turns the seated Mayor to look at the Town Hall door, like someone receiving visitors. */
    private static void face(ServerLevel level, UUID settlement, Villager mayor) {
        for (var b : ProfessionSavedData.get(level.getServer()).buildings(settlement)) if (b.active() && b.kind() == BuildingKind.TOWN_HALL) {
            float yaw = (float) Math.toDegrees(Math.atan2(-(b.entrance().getX() + .5 - mayor.getX()), b.entrance().getZ() + .5 - mayor.getZ()));
            mayor.setYRot(yaw); mayor.setYHeadRot(yaw); mayor.setYBodyRot(yaw); mayor.yRotO = yaw; mayor.yHeadRotO = yaw;
            return;
        }
    }

    /**
     * A standable spot deep inside the Town Hall (about two thirds of the way from the door to the back wall),
     * off the door line so nobody's path is blocked; null while there is no Town Hall.
     */
    static BlockPos seat(ServerLevel level, UUID settlement) {
        for (var b : ProfessionSavedData.get(level.getServer()).buildings(settlement)) {
            if (!b.active() || b.kind() != BuildingKind.TOWN_HALL || b.template().getPath().contains("converted")) continue;
            var e = b.entrance(); var box = b.bounds();
            int cx = (box.minX() + box.maxX()) / 2, cz = (box.minZ() + box.maxZ()) / 2;
            var inward = Direction.getNearest(cx - e.getX(), 0, cz - e.getZ());
            int depth = inward.getAxis() == Direction.Axis.X ? box.maxX() - box.minX() : box.maxZ() - box.minZ();
            int far = Math.max(4, depth * 2 / 3 + 2);
            for (int step = far; step >= 4; step--) for (int side : new int[]{1, -1, 2, -2, 0}) for (int dy = 1; dy <= 2; dy++) {
                var pos = e.relative(inward, step).relative(inward.getClockWise(), side).above(dy);
                if (!level.hasChunkAt(pos)) return null;
                if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                        && level.getBlockState(pos).getBlock() != dev.livingkingdoms.block.KingdomBlocks.BUILDING_LEDGER.get()) return pos;
            }
        }
        return null;
    }

    public static void clear() { WALKING.clear(); }
}

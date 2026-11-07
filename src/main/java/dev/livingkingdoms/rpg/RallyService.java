package dev.livingkingdoms.rpg;

import dev.livingkingdoms.citizen.domain.Citizen;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.CitizenRoutine;
import dev.livingkingdoms.rpg.domain.RallyPolicy;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Call villagers" from the Town Hall / Mayor citizens view. One transient rally per settlement, bounded,
 * never persisted. Citizens answer from their existing 20/40-tick work ticks (no extra entity scan);
 * guards keep patrolling, the Mayor stays put and a threatened settlement keeps its shelter rules.
 */
public final class RallyService {
    private static final int MAX_RALLIES = 256;
    private static final Map<UUID, RallyPolicy.Rally> ACTIVE = new LinkedHashMap<>();
    private RallyService() {}

    public static boolean callAll(ServerPlayer player, UUID settlement) { return call(player, settlement, null); }
    public static boolean callOne(ServerPlayer player, UUID settlement, UUID citizen) {
        return citizen != null && call(player, settlement, citizen);
    }

    private static boolean call(ServerPlayer player, UUID settlementId, UUID citizen) {
        var settlement = SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        if (settlement == null || !settlement.faction().isAllied() || !KingdomOnboardingService.canHelp(player, settlement)) return false;
        String dimension = player.level().dimension().location().toString();
        if (!settlement.territory().contains(dimension, player.getBlockX(), player.getBlockZ())) return false;
        if (citizen != null && CitizenSavedData.get(player.server).citizen(citizen)
                .filter(c -> c.settlementId().equals(settlementId) && c.state() == CitizenState.ACTIVE).isEmpty()) return false;
        if (!ACTIVE.containsKey(settlementId) && ACTIVE.size() >= MAX_RALLIES) ACTIVE.remove(ACTIVE.keySet().iterator().next());
        ACTIVE.put(settlementId, RallyPolicy.call(dimension, player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                now(player.serverLevel()), RallyPolicy.DEFAULT_DURATION_TICKS, citizen));
        return true;
    }

    public static boolean cancel(ServerPlayer player, UUID settlementId) {
        var settlement = SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        if (settlement == null || !KingdomOnboardingService.canHelp(player, settlement)) return false;
        return ACTIVE.remove(settlementId) != null;
    }

    public static boolean active(ServerLevel level, UUID settlement) {
        var rally = ACTIVE.get(settlement);
        if (rally != null && !rally.active(now(level))) { ACTIVE.remove(settlement); return false; }
        return rally != null;
    }

    /** Side-effect free: whether {@link #steer} would take control of this citizen now. */
    public static boolean answers(Villager worker, Citizen citizen) {
        if (ACTIVE.isEmpty() || citizen.role() == CitizenRole.MAYOR || !(worker.level() instanceof ServerLevel level)) return false;
        var rally = ACTIVE.get(citizen.settlementId());
        return rally != null && RallyPolicy.effective(rally, now(level), citizen.id(), level.dimension().location().toString()).isPresent()
                && !worker.isNoAi() && !worker.isPassenger() && !worker.isLeashed() && !CitizenRoutine.threatened(worker, citizen)
                && RallyPolicy.withinReach(worker.getX() - rally.x(), worker.getZ() - rally.z());
    }

    /** Returns true when the rally took control of this citizen for the current work tick. */
    public static boolean steer(Villager worker, Citizen citizen) {
        if (ACTIVE.isEmpty() || citizen.role() == CitizenRole.MAYOR || !(worker.level() instanceof ServerLevel level)) return false;
        var rally = ACTIVE.get(citizen.settlementId());
        if (rally == null) return false;
        long now = now(level);
        if (!rally.active(now)) { ACTIVE.remove(citizen.settlementId()); return false; }
        if (RallyPolicy.effective(rally, now, citizen.id(), level.dimension().location().toString()).isEmpty()) return false;
        if (worker.isNoAi() || worker.isPassenger() || worker.isLeashed() || CitizenRoutine.threatened(worker, citizen)) return false;
        if (!RallyPolicy.withinReach(worker.getX() - rally.x(), worker.getZ() - rally.z())) return false;
        if (worker.isSleeping()) worker.stopSleeping();
        var target = new BlockPos(rally.x(), rally.y(), rally.z());
        if (worker.distanceToSqr(rally.x() + .5, rally.y(), rally.z() + .5) <= RallyPolicy.ARRIVAL_DISTANCE_SQ) {
            worker.getNavigation().stop();
            worker.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            worker.getLookControl().setLookAt(rally.x() + .5, rally.y() + 1.5, rally.z() + .5);
        } else {
            worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, .7F, 2));
            worker.getNavigation().moveTo(rally.x() + .5, rally.y(), rally.z() + .5, .7);
        }
        return true;
    }

    public static void clear() { ACTIVE.clear(); }
    private static long now(ServerLevel level) { return level.getServer().overworld().getGameTime(); }
}

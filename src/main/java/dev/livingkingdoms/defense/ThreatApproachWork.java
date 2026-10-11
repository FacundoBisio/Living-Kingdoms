package dev.livingkingdoms.defense;

import dev.livingkingdoms.config.DefenseConfig;
import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.defense.persistence.DefenseSavedData;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import java.util.EnumSet;
import java.util.UUID;

/** Local goals on actual loaded members. Native combat keeps control of targets inside the objective. */
public final class ThreatApproachWork {
    private ThreatApproachWork() {}
    public static void onJoin(EntityJoinLevelEvent event) {
        if(!event.isCanceled() && event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob mob) install(mob);
    }
    public static void install(Mob mob) {
        if(!(mob.level() instanceof ServerLevel level)) return;
        var party=EncounterSavedData.get(level.getServer()).forMember(mob.getUUID()).orElse(null);
        if(party==null || DefenseSavedData.get(level.getServer()).forParty(party.id()).filter(SettlementThreatEvent::threatened).isEmpty()
                || mob.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof ApproachGoal)) return;
        mob.goalSelector.addGoal(1,new ApproachGoal(mob,party.id()));
    }
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if(event.isCanceled() || !(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)
                || event.getNewAboutToBeSetTarget()==null) return;
        var party=EncounterSavedData.get(level.getServer()).forMember(mob.getUUID()).orElse(null);
        if(party==null) return;
        var threat=DefenseSavedData.get(level.getServer()).forParty(party.id()).filter(SettlementThreatEvent::threatened).orElse(null);
        if(threat==null) return;
        var settlement=SettlementSavedData.get(level.getServer()).get(threat.settlementId()).orElse(null);
        if(settlement==null) return;
        var target=event.getNewAboutToBeSetTarget();
        double bound=objectiveRadius(settlement);
        // During travel, permit immediate self-defense nearby, rather than a pursuit across the world.
        boolean allowed=threat.state()==ThreatState.ACTIVE?within(target.getX(),target.getZ(),settlement,bound)
                :mob.distanceToSqr(target)<=144;
        if(!allowed) event.setCanceled(true);
    }
    public static double objectiveRadius(Settlement settlement) { return settlement.territory().radius()+DefenseConfig.OBJECTIVE_MARGIN.get(); }
    private static boolean within(double x,double z,Settlement s,double radius) {
        return ThreatApproachPolicy.within(x,z,s.territory().x()+.5,s.territory().z()+.5,radius);
    }
    private static final class ApproachGoal extends Goal {
        private final Mob mob;
        private final UUID party;
        private Settlement settlement;
        private SettlementThreatEvent event;
        private int nextMove,failures;
        private Vec3 previous;
        ApproachGoal(Mob mob,UUID party) { this.mob=mob; this.party=party; setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            var level=(ServerLevel)mob.level();
            event=DefenseSavedData.get(level.getServer()).forParty(party).filter(SettlementThreatEvent::threatened).orElse(null);
            if(event==null) return false;
            settlement=SettlementSavedData.get(level.getServer()).get(event.settlementId()).orElse(null);
            if(settlement==null) return false;
            var target=mob.getTarget();
            if(target!=null && (event.state()==ThreatState.ACTIVE && (!within(target.getX(),target.getZ(),settlement,objectiveRadius(settlement))
                    || !within(mob.getX(),mob.getZ(),settlement,objectiveRadius(settlement)))
                    || event.state()!=ThreatState.ACTIVE && mob.distanceToSqr(target)>144)) { mob.setTarget(null); target=null; }
            return target==null;
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void stop() { mob.getNavigation().stop(); }
        @Override public void tick() {
            if(mob.tickCount<nextMove) return;
            nextMove=mob.tickCount+80;
            var level=(ServerLevel)mob.level();
            double angle=Math.floorMod(mob.getUUID().hashCode(),16)*Math.PI/8;
            double rally=Math.min(10,dev.livingkingdoms.profession.GuardWork.defense(settlement)/2);
            double x=settlement.territory().x()+.5+Math.cos(angle)*rally,z=settlement.territory().z()+.5+Math.sin(angle)*rally;
            double dx=x-mob.getX(),dz=z-mob.getZ(),distance=Math.hypot(dx,dz);
            if(distance<3) { failures=0; mob.getNavigation().stop(); return; }
            double step=Math.min(12,distance);
            var proposed=BlockPos.containing(mob.getX()+dx/distance*step,mob.getY(),mob.getZ()+dz/distance*step);
            // Neighbor chunks are checked before vanilla navigation constructs its local path cache.
            for(int cx=(proposed.getX()-16)>>4;cx<=(proposed.getX()+16)>>4;cx++)
                for(int cz=(proposed.getZ()-16)>>4;cz<=(proposed.getZ()+16)>>4;cz++)
                    if(!level.getChunkSource().hasChunk(cx,cz)) { mob.getNavigation().stop(); previous=null; return; }
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,proposed.getX(),proposed.getZ());
            boolean stalled=previous!=null && previous.distanceToSqr(mob.position())<.25;
            previous=mob.position();
            boolean moved=Math.abs(y-mob.getY())<=4 && level.getFluidState(proposed.atY(y)).isEmpty()
                    && mob.getNavigation().moveTo(proposed.getX()+.5,y,proposed.getZ()+.5,DefenseConfig.APPROACH_SPEED.get());
            failures=!moved || stalled?failures+1:0;
            if(failures>=DefenseConfig.ROUTE_FAILURE_LIMIT.get()) {
                mob.getNavigation().stop(); DefenseService.fail(level.getServer(),event,ThreatOutcome.ROUTE_BLOCKED);
            }
        }
    }
}

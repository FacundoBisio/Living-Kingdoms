package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.BuilderConfig;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.*;

/** A local native goal travels to the reserved site; persisted project receipts own all work and XP. */
public final class BuilderWork {
    private static final Map<Villager,ConstructionGoal> CONTROLLED=new WeakHashMap<>();
    private BuilderWork() {}

    public static Optional<Profession> profile(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !v.isAlive() || !v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) return Optional.empty();
        var c=CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).orElse(null);
        if(c==null || c.homeId()==null || !CitizenService.canApply(c,v)) return Optional.empty();
        return ProfessionSavedData.get(level.getServer()).profession(c.id()).filter(p -> p.active() && p.type()==ProfessionType.BUILDER
                && p.settlementId().equals(c.settlementId()));
    }
    public static boolean assigned(Villager v) { return profile(v).isPresent(); }
    /** Direct danger is independent of settlement-wide alerts and permits vanilla panic/survival behaviors. */
    public static boolean inDanger(Villager v) {
        var attacker=v.getLastHurtByMob();
        return v.hurtTime>0 || v.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY)
                || attacker!=null && attacker.isAlive() && attacker.level()==v.level()
                    && v.tickCount-v.getLastHurtByMobTimestamp()<100 && v.distanceToSqr(attacker)<100;
    }
    public static void control(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) throw new IllegalStateException("Builder control requires server thread");
        var p=profile(v).orElse(null);
        if(p==null || inDanger(v) || !homeReady(v) || ConstructionService.projectForBuilder(level.getServer(),p.citizenId()).isEmpty()) { release(v); return; }
        if(!CONTROLLED.containsKey(v)) {
            v.getBrain().stopAll(level,v); v.getBrain().removeAllBehaviors(); v.getNavigation().stop();
            var goal=new ConstructionGoal(v); CONTROLLED.put(v,goal); v.goalSelector.addGoal(1,goal);
        }
    }
    public static void release(Villager v) {
        var goal=CONTROLLED.remove(v);
        if(goal!=null) {
            v.goalSelector.removeGoal(goal); v.getNavigation().stop();
            if(v.level() instanceof ServerLevel level) v.refreshBrain(level);
        }
    }
    public static void onJoin(EntityJoinLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Villager v && assigned(v)) control(v);
    }
    public static void onLeave(EntityLeaveLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Villager v) {
            var goal=CONTROLLED.remove(v); if(goal!=null) v.goalSelector.removeGoal(goal);
            // Unloading the vanilla entity pauses work; it must retain the persisted exclusive assignment.
        }
    }
    public static void onTick(EntityTickEvent.Post event) {
        if(event.getEntity() instanceof Villager v && v.level() instanceof ServerLevel && v.tickCount%20==0
                && v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) tick(v);
    }
    public static boolean tick(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        var citizen=CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).orElse(null);
        if(citizen!=null && citizen.homeId()!=null && v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)
                && citizen.id().equals(v.getPersistentData().getUUID(CitizenService.CITIZEN_ID_KEY))) {
            var home=CitizenSavedData.get(level.getServer()).housing(citizen.homeId()).orElse(null);
            if(home!=null && home.convertedBed() && ConvertedBuilderHomes.status(level,citizen)==ConvertedBuilderHomes.Status.INVALID) {
                CitizenSavedData.get(level.getServer()).disableConvertedBedHome(home.id());
                ProfessionSavedData.get(level.getServer()).profession(citizen.id()).filter(p -> p.type()==ProfessionType.BUILDER).ifPresent(p -> {
                    ProfessionSavedData.get(level.getServer()).retire(citizen.id());
                }); ConstructionService.releaseBuilder(level.getServer(),citizen.id()); release(v); return false;
            }
        }
        var p=profile(v).orElse(null);
        if(p==null) {
            CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).ifPresent(c -> {
                var old=ProfessionSavedData.get(level.getServer()).profession(c.id()).orElse(null);
                if(v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)
                        && c.id().equals(v.getPersistentData().getUUID(CitizenService.CITIZEN_ID_KEY))
                        && (old==null || old.type()!=ProfessionType.BUILDER || !old.active()
                            || c.state()!=dev.livingkingdoms.citizen.domain.CitizenState.ACTIVE || c.homeId()==null || !v.isAlive())) {
                    if(old!=null && old.active() && old.type()==ProfessionType.BUILDER) ProfessionSavedData.get(level.getServer()).retire(c.id());
                    ConstructionService.releaseBuilder(level.getServer(),c.id());
                }
            }); release(v); return false;
        }
        if(inDanger(v)) {
            release(v); var data=ProfessionSavedData.get(level.getServer());
            data.replace(p,p.work(WorkState.BLOCKED,Math.max(p.nextWorkAt(),ImmigrationService.now(level.getServer())+20),0,0)); return false;
        }
        if(!homeReady(v)) {
            release(v); var data=ProfessionSavedData.get(level.getServer());
            data.replace(p,p.work(WorkState.UNLOADED,Math.max(p.nextWorkAt(),ImmigrationService.now(level.getServer())+20),0,0)); return false;
        }
        var entry=ConstructionService.projectForBuilder(level.getServer(),p.citizenId());
        if(entry.isEmpty()) { ConstructionService.assignReady(level.getServer(),p.settlementId()); entry=ConstructionService.projectForBuilder(level.getServer(),p.citizenId()); }
        if(entry.isEmpty()) {
            release(v); ProfessionSavedData.get(level.getServer()).replace(p,p.work(WorkState.IDLE,p.nextWorkAt(),0,0)); return false;
        }
        control(v);
        var goal=CONTROLLED.get(v); return goal!=null && goal.work();
    }
    public static void onDeath(LivingDeathEvent event) {
        if(!event.isCanceled() && event.getEntity() instanceof Villager v && v.level() instanceof ServerLevel level) retire(level,v);
    }
    public static void onDamage(LivingDamageEvent.Post event) {
        if(event.getNewDamage()>0 && event.getEntity() instanceof Villager v && v.level() instanceof ServerLevel && assigned(v)) release(v);
    }
    public static void onConversion(LivingConversionEvent.Post event) {
        if(event.getEntity() instanceof Villager v && !(event.getOutcome() instanceof Villager) && v.level() instanceof ServerLevel level) retire(level,v);
    }
    private static void retire(ServerLevel level,Villager v) {
        CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).ifPresent(c -> {
            var p=ProfessionSavedData.get(level.getServer()).profession(c.id()).orElse(null);
            if(p!=null && p.type()==ProfessionType.BUILDER) {
                ProfessionSavedData.get(level.getServer()).retire(c.id()); ConstructionService.releaseBuilder(level.getServer(),c.id());
            }
        }); release(v);
    }
    public static boolean homeReady(Villager v) {
        if(!(v.level() instanceof ServerLevel level)) return false;
        return CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).filter(c -> ConvertedBuilderHomes.homeAvailable(level,c)).isPresent();
    }

    private static final class ConstructionGoal extends Goal {
        private final Villager v;
        private BlockPos previousTarget;
        private net.minecraft.world.phys.Vec3 previousPosition;
        ConstructionGoal(Villager v) { this.v=v; setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() {
            return !inDanger(v) && homeReady(v) && profile(v).filter(p -> ConstructionService.projectForBuilder(((ServerLevel)v.level()).getServer(),p.citizenId()).isPresent()).isPresent();
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void tick() { if(v.tickCount%20==0) work(); }
        @Override public void stop() { v.getNavigation().stop(); }
        boolean work() {
            var level=(ServerLevel)v.level(); var p=profile(v).orElse(null);
            if(p==null || inDanger(v)) return false;
            var data=ProfessionSavedData.get(level.getServer()); long now=ImmigrationService.now(level.getServer());
            if(now<p.nextWorkAt()) return false;
            long next=now+BuilderConfig.WORK_INTERVAL.get();
            var entry=ConstructionService.projectForBuilder(level.getServer(),p.citizenId()).orElse(null); if(entry==null) return false;
            var s=SettlementSavedData.get(level.getServer()).get(p.settlementId()).orElse(null);
            var workplace=data.building(p.workplaceId()).orElse(null);
            if(s==null || !s.faction().isAllied() || workplace==null || !workplace.active() || !workplace.supports(ProfessionType.BUILDER)) {
                data.retire(p.citizenId()); ConstructionService.releaseBuilder(level.getServer(),p.citizenId()); return false;
            }
            if(v.isNoAi() || v.isPassenger() || v.isLeashed() || v.isTrading() || v.isSleeping()) {
                v.getNavigation().stop(); data.replace(p,p.work(v.isSleeping()?WorkState.SLEEPING:WorkState.IDLE,next,0,0)); return false;
            }
            var at=ConstructionService.workPoint(entry);
            if(!entry.plan().territory().dimension().equals(level.dimension().location().toString())
                    || !level.getChunkSource().hasChunk(at.getX()>>4,at.getZ()>>4)
                    || !ConstructionService.isLoaded(level.getServer(),entry)) {
                v.getNavigation().stop(); data.replace(p,p.work(WorkState.UNLOADED,next,0,0)); return false;
            }
            // Finishing places the authoritative blueprint and its paths. Leave the entire
            // placement area before retrying, even if work was credited from a nearby point.
            double range=entry.project().workTicks()>=entry.project().durationTicks()?.75:BuilderConfig.WORK_RANGE.get();
            if(v.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)>range*range) {
                boolean stalled=at.equals(previousTarget) && previousPosition!=null && previousPosition.distanceToSqr(v.position())<.25;
                previousTarget=at; previousPosition=v.position(); v.getNavigation().setCanFloat(true);
                boolean moved=v.getNavigation().moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,.65);
                int failures=!moved || stalled?Math.min(5,p.navigationFailures()+1):0;
                if(failures>=5) v.getNavigation().stop();
                data.replace(p,p.work(moved && failures<5?WorkState.WORKING:WorkState.BLOCKED,failures>=5?now+100:next,0,failures)); return false;
            }
            previousPosition=null; previousTarget=null; v.getNavigation().stop();
            v.getLookControl().setLookAt(at.getX()+.5,at.getY()+1,at.getZ()+.5);
            if(!data.replace(p,p.work(WorkState.WORKING,next,0,0))) return false;
            boolean worked=ConstructionService.contribute(v);
            if(worked) v.swing(InteractionHand.MAIN_HAND);
            return worked;
        }
    }
}

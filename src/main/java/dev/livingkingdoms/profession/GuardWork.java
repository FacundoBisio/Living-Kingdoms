package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.GuardConfig;
import dev.livingkingdoms.encounter.EncounterMember;
import dev.livingkingdoms.faction.*;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.monster.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.*;

/** One local native goal per loaded assigned citizen. No world/entity loops or chunk tickets. */
public final class GuardWork {
    private static final Map<Villager,DefenseGoal> CONTROLLED=new WeakHashMap<>();
    private static final String DAMAGE_CREDIT="livingkingdoms:guard_damage_credit";
    private static final Map<LivingEntity,Deque<Float>> BEFORE_DAMAGE=new WeakHashMap<>();
    private GuardWork() {}
    public static Optional<Profession> profile(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !v.isAlive() || !v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) return Optional.empty();
        var citizen=CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).orElse(null);
        if(citizen==null || citizen.homeId()==null || !CitizenService.canApply(citizen,v)) return Optional.empty();
        return ProfessionSavedData.get(level.getServer()).profession(citizen.id()).filter(p -> p.active() && p.type()==ProfessionType.GUARD);
    }
    public static boolean assigned(Villager v) { return profile(v).isPresent(); }
    public static void control(Villager v) {
        var p=profile(v).orElse(null); if(p==null) return;
        var level=(ServerLevel)v.level();
        if(!CONTROLLED.containsKey(v)) {
            v.getBrain().stopAll(level,v); v.getBrain().removeAllBehaviors(); v.getNavigation().stop();
            var goal=new DefenseGoal(v,p); CONTROLLED.put(v,goal); v.goalSelector.addGoal(1,goal);
        }
        GuardEquipment.apply(v,p);
    }
    public static void release(Villager v) {
        var goal=CONTROLLED.remove(v);
        if(goal!=null) { v.goalSelector.removeGoal(goal); v.setTarget(null); v.getNavigation().stop(); if(v.level() instanceof ServerLevel level) v.refreshBrain(level); }
        // Covers assignment removal while the entity was unloaded: vanilla NBT is the equipment authority.
        GuardEquipment.remove(v);
    }
    public static void onJoin(EntityJoinLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel) {
            if(event.getEntity() instanceof ItemEntity item && GuardEquipment.issued(item.getItem())) { event.setCanceled(true); return; }
            if(event.getEntity() instanceof Villager v) { if(assigned(v)) control(v); else GuardEquipment.remove(v); }
        }
    }
    public static void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Villager v) {
            var goal=CONTROLLED.remove(v); if(goal!=null) v.goalSelector.removeGoal(goal);
        }
    }
    public static void onTick(EntityTickEvent.Post event) {
        if(event.getEntity() instanceof Villager v && v.level() instanceof ServerLevel && v.tickCount%20==0 && v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) {
            if(assigned(v)) control(v); else if(CONTROLLED.containsKey(v)) release(v);
        }
    }
    public static void onConversion(LivingConversionEvent.Post event) {
        if(!(event.getEntity().level() instanceof ServerLevel)) return;
        var before=EncounterMember.read(event.getEntity()); var after=EncounterMember.read(event.getOutcome());
        if(before.isPresent() && before.equals(after) && event.getEntity().getPersistentData().contains(DAMAGE_CREDIT))
            event.getOutcome().getPersistentData().put(DAMAGE_CREDIT,event.getEntity().getPersistentData().getCompound(DAMAGE_CREDIT).copy());
    }
    public static void onDeath(LivingDeathEvent event) {
        if(!event.isCanceled() && event.getEntity() instanceof Villager v && v.level() instanceof ServerLevel) GuardEquipment.remove(v);
    }
    public static boolean hostile(LivingEntity other) {
        if(!other.isAlive()) return false;
        var faction=FactionEntityResolver.combatFaction(other);
        if(faction.isPresent()) return GuardPolicy.hostile(faction.orElseThrow());
        // Metadata wins, even if its roster is stale. Unrelated monsters only attack when native aggression targets a citizen.
        if(EncounterMember.read(other).isPresent() || NpcIdentity.read(other).isPresent()) return false;
        return other instanceof Monster mob && mob.getTarget() instanceof Villager citizen && CitizenSavedData.get(((ServerLevel)other.level()).getServer()).byEntity(citizen.getUUID()).isPresent();
    }
    public static void beforeDamage(LivingDamageEvent.Pre event) {
        if(event.getEntity().level() instanceof ServerLevel && event.getSource().getEntity() instanceof Villager guard && assigned(guard))
            BEFORE_DAMAGE.computeIfAbsent(event.getEntity(),e -> new ArrayDeque<>()).push(event.getEntity().getHealth());
    }
    public static void onDamage(LivingDamageEvent.Post event) {
        Float before=null;
        if(event.getSource().getEntity() instanceof Villager) {
            var pending=BEFORE_DAMAGE.get(event.getEntity());
            if(pending!=null && !pending.isEmpty()) { before=pending.pop(); if(pending.isEmpty()) BEFORE_DAMAGE.remove(event.getEntity()); }
        }
        if(!(event.getEntity().level() instanceof ServerLevel level) || !(event.getSource().getEntity() instanceof Villager guard)
                || !(event.getEntity() instanceof Mob) || before==null || !Float.isFinite(event.getNewDamage()) || event.getNewDamage()<=0) return;
        var p=profile(guard).orElse(null); if(p==null) return;
        // Post damage also credits the lethal hit while the party membership still exists.
        var faction=FactionEntityResolver.combatFaction(event.getEntity());
        if(faction.filter(GuardPolicy::hostile).isEmpty()) return;
        var s=SettlementSavedData.get(level.getServer()).get(p.settlementId()).orElse(null);
        if(s==null || !inside(s,event.getEntity(),defense(s)+GuardConfig.CHASE_RANGE.get())) return;
        var root=event.getEntity().getPersistentData(); var credits=root.getCompound(DAMAGE_CREDIT); String key=p.citizenId().toString();
        if(!credits.contains(key) && credits.size()>=128) return;
        double previous=credits.getDouble(key), oldTotal=credits.getDouble("total");
        // Damage beyond remaining health is excluded; the finite shared victim budget survives heal/reload.
        double actual=Math.min(event.getNewDamage(),Math.max(0,before-event.getEntity().getHealth()));
        double accepted=Math.min(actual,Math.max(0,event.getEntity().getMaxHealth()-oldTotal));
        if(accepted<=0) return;
        double next=previous+accepted; credits.putDouble(key,next); credits.putDouble("total",oldTotal+accepted); root.put(DAMAGE_CREDIT,credits);
        int xp=((int)(next/4)-(int)(previous/4))*GuardConfig.XP_PER_UNIT.get();
        if(xp>0 && ProfessionSavedData.get(level.getServer()).guardExperience(p,xp,GuardConfig.LEVEL_CAP.get(),GuardConfig.XP_STEP.get()))
            GuardEquipment.apply(guard,ProfessionSavedData.get(level.getServer()).profession(p.citizenId()).orElseThrow());
    }
    public static double defense(Settlement s) { return Math.min(s.territory().radius(),GuardConfig.DEFENSE_RANGE.get()); }
    private static boolean inside(Settlement s,Entity e,double range) { return GuardPolicy.within(e.getX(),e.getZ(),s.territory().x()+.5,s.territory().z()+.5,range); }

    private static final class DefenseGoal extends Goal {
        private final Villager v;
        private final UUID citizen,settlement;
        private int searchAt,attackAt,moveAt,waypoint,patrolAt;
        private BlockPos patrol;
        DefenseGoal(Villager v,Profession p) { this.v=v; citizen=p.citizenId(); settlement=p.settlementId(); setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); searchAt=v.tickCount+Math.floorMod(v.getId(),20); }
        @Override public boolean canUse() { return v.isAlive() && !v.isNoAi(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        private void state(WorkState state) {
            var d=ProfessionSavedData.get(((ServerLevel)v.level()).getServer()); var p=d.profession(citizen).orElse(null);
            if(p!=null && p.active() && p.type()==ProfessionType.GUARD && p.workState()!=state) d.replace(p,p.work(state,p.nextWorkAt(),p.cropCursor(),0));
        }
        @Override public void tick() {
            var level=(ServerLevel)v.level();
            var s=SettlementSavedData.get(level.getServer()).get(settlement).orElse(null);
            if(s==null || !s.faction().isAllied() || !s.territory().dimension().equals(level.dimension().location().toString())) { v.setTarget(null); v.getNavigation().stop(); return; }
            if(v.isPassenger() || v.isLeashed() || v.isTrading()) { v.setTarget(null); v.getNavigation().stop(); state(WorkState.IDLE); return; }
            double defense=defense(s), chase=defense+GuardConfig.CHASE_RANGE.get();
            LivingEntity target=v.getTarget();
            if(target!=null && (!hostile(target) || target.level()!=level || !inside(s,target,chase) || !inside(s,v,chase))) {
                v.setTarget(null); v.getNavigation().stop(); target=null; patrol=null; state(WorkState.RETURNING);
            }
            if(v.tickCount>=searchAt && target==null && inside(s,v,defense)) {
                boolean night=Math.floorMod(level.getDayTime(),24000)>=13000;
                searchAt=v.tickCount+(night?Math.max(20,GuardConfig.SEARCH_INTERVAL.get()/2):GuardConfig.SEARCH_INTERVAL.get());
                target=level.getEntitiesOfClass(Mob.class,v.getBoundingBox().inflate(GuardConfig.SEARCH_RANGE.get(),6,GuardConfig.SEARCH_RANGE.get()),
                        e -> e!=v && hostile(e) && inside(s,e,defense) && v.hasLineOfSight(e)).stream().min(Comparator.comparingDouble(v::distanceToSqr)).orElse(null);
                v.setTarget(target);
                if(target!=null && EncounterMember.read(target).isPresent()) dev.livingkingdoms.defense.DefenseService.detectMember(level,target);
            }
            if(target!=null) {
                if(v.isSleeping()) v.stopSleeping(); state(WorkState.ENGAGING);
                v.getLookControl().setLookAt(target,30,30);
                if(v.tickCount>=moveAt) { v.getNavigation().moveTo(target,.8); moveAt=v.tickCount+10; }
                if(v.tickCount>=attackAt && v.distanceToSqr(target)<=4 && v.hasLineOfSight(target)) {
                    attackAt=v.tickCount+25; v.swing(InteractionHand.MAIN_HAND);
                    var p=ProfessionSavedData.get(level.getServer()).profession(citizen).orElseThrow();
                    target.hurt(v.damageSources().mobAttack(v),(float)(GuardConfig.BASE_DAMAGE.get()+Math.min(2,(p.level().value()-1)*GuardConfig.DAMAGE_PER_LEVEL.get())));
                }
                return;
            }
            if(v.isSleeping()) { state(WorkState.SLEEPING); return; }
            BlockPos center=new BlockPos(s.territory().x(),s.territory().y(),s.territory().z());
            if(!inside(s,v,defense)) { move(center,level); state(WorkState.RETURNING); return; }
            if(patrol==null || v.tickCount>=patrolAt) {
                patrolAt=v.tickCount+160;
                var points=ProfessionSavedData.get(level.getServer()).buildings(settlement).stream().filter(b -> b.active() && switch(b.kind()) {
                    case CORE,TOWN_HALL,BARRACKS,WATCHTOWER -> true; default -> false;
                }).map(b -> b.entrance().above()).filter(pos -> GuardPolicy.within(pos.getX(),pos.getZ(),center.getX(),center.getZ(),defense) && level.hasChunkAt(pos)).limit(12).toList();
                patrol=points.isEmpty()?center:points.get(Math.floorMod(waypoint++,points.size()));
            }
            state(v.distanceToSqr(patrol.getX()+.5,patrol.getY(),patrol.getZ()+.5)<4?WorkState.IDLE:WorkState.PATROLLING);
            move(patrol,level);
        }
        private void move(BlockPos pos,ServerLevel level) {
            if(v.tickCount<moveAt) return; moveAt=v.tickCount+40;
            if(level.hasChunkAt(pos)) v.getNavigation().moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,.65); else {v.getNavigation().stop();state(WorkState.UNLOADED);}
        }
        @Override public void stop() { v.setTarget(null); v.getNavigation().stop(); }
    }
}

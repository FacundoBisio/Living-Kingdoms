package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.*;

/** One loaded worker, one bounded crop search, one real harvest per persisted opportunity. */
public final class FarmerWork {
    private static final Map<Villager,Boolean> CONTROLLED=new WeakHashMap<>();
    private record NavigationAttempt(BlockPos target,net.minecraft.world.phys.Vec3 position) {}
    private static final Map<Villager,NavigationAttempt> NAVIGATION=new WeakHashMap<>();
    private FarmerWork() {}
    public static void control(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) throw new IllegalStateException("Worker control requires server thread");
        CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).flatMap(c -> SettlementSavedData.get(level.getServer()).get(c.settlementId()))
                .ifPresent(s -> v.restrictTo(new BlockPos(s.territory().x(),s.territory().y(),s.territory().z()),s.territory().radius()));
        if(CONTROLLED.putIfAbsent(v,Boolean.TRUE)==null) {
            // Suppress vanilla career crop behaviors only during an explicit LK assignment, preserving all trades/data.
            v.getBrain().stopAll(level,v); v.getBrain().removeAllBehaviors(); v.getNavigation().stop();
        }
    }
    public static void release(Villager v) {
        NAVIGATION.remove(v);
        if(CONTROLLED.remove(v)!=null && v.level() instanceof ServerLevel level) { v.getNavigation().stop(); v.refreshBrain(level); }
    }
    public static boolean assigned(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) return false;
        var citizen=CitizenSavedData.get(level.getServer()).byEntity(v.getUUID()).orElse(null);
        if(citizen==null || !CitizenService.canApply(citizen,v)) return false;
        return ProfessionSavedData.get(level.getServer()).profession(citizen.id())
                .filter(p -> p.active() && p.type()==ProfessionType.FARMER).isPresent();
    }
    public static void onJoin(EntityJoinLevelEvent event) {
        if(event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Villager v && assigned(v)) control(v);
    }
    public static void onTick(EntityTickEvent.Post event) {
        if(event.getEntity() instanceof Villager v && v.tickCount%20==0 && v.level() instanceof ServerLevel
                && v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) tick(v);
    }
    public static boolean tick(Villager v) {
        if(!(v.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        var people=CitizenSavedData.get(level.getServer()); var citizen=people.byEntity(v.getUUID()).orElse(null);
        var data=ProfessionSavedData.get(level.getServer()); var p=citizen==null?null:data.profession(citizen.id()).orElse(null);
        if(p==null || p.type()!=ProfessionType.FARMER || !p.active()) { release(v); return false; }
        if(citizen.state()!=CitizenState.ACTIVE || citizen.homeId()==null || !v.isAlive()) { data.retire(citizen.id()); release(v); return false; }
        if(!CitizenService.canApply(citizen,v) || !v.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY) || !v.getPersistentData().getUUID(CitizenService.CITIZEN_ID_KEY).equals(citizen.id())
                || !p.settlementId().equals(citizen.settlementId()) || !SettlementSavedData.get(level.getServer()).get(citizen.settlementId())
                    .filter(s -> s.faction().isAllied() && s.territory().dimension().equals(level.dimension().location().toString())).isPresent()) return false;
        control(v); long now=ImmigrationService.now(level.getServer()); if(now<p.nextWorkAt()) return false;
        int cooldown=FarmerProgression.cooldown(ProfessionConfig.WORK_COOLDOWN.get(),p.level().value(),ProfessionConfig.LEVEL_CAP.get());
        long next=now+cooldown;
        if(v.isNoAi() || v.isPassenger() || v.isLeashed() || v.isTrading()) { v.getNavigation().stop(); data.replace(p,p.work(WorkState.IDLE,next,p.cropCursor(),0)); return false; }
        long daytime=Math.floorMod(level.getDayTime(),24000);
        if(daytime<1000 || daytime>=11000 || v.isSleeping()) {
            var home=people.housing(citizen.homeId()).orElse(null);
            boolean moved=home!=null && loaded(level,home.entrance()) && move(v,home.entrance().above());
            data.replace(p,p.work(v.isSleeping()?WorkState.SLEEPING:moved?WorkState.RETURNING_HOME:WorkState.IDLE,next,p.cropCursor(),0)); return false;
        }
        var farm=data.building(p.workplaceId()).orElse(null);
        if(farm==null || !farm.active() || farm.kind()!=dev.livingkingdoms.structure.BuildingKind.FARM) { data.retire(citizen.id()); release(v); return false; }
        if(!farm.dimension().equals(level.dimension().location().toString()) || !loadedFarm(level,farm)) {
            v.getNavigation().stop();
            data.replace(p,p.work(WorkState.UNLOADED,next,p.cropCursor(),0)); return false;
        }
        data.ensureFood(p.settlementId(),ProfessionConfig.FOOD_CAPACITY.get());
        if(data.food(p.settlementId(),ProfessionConfig.FOOD_CAPACITY.get()).stock()>=ProfessionConfig.FOOD_CAPACITY.get()) {
            v.getNavigation().stop();
            data.replace(p,p.work(WorkState.STORAGE_FULL,next,p.cropCursor(),0)); return false;
        }
        int cursor=p.cropCursor();
        for(int i=0;i<ProfessionConfig.SCAN_BUDGET.get();i++) {
            BlockPos crop=farm.cropPosition(cursor++); cursor%=farm.cropVolume();
            if(!farm.containsCrop(crop) || !loaded(level,crop)) continue;
            var state=level.getBlockState(crop); var harvest=CropPolicy.mature(state);
            if(harvest.isEmpty()) continue;
            if(v.distanceToSqr(crop.getX()+.5,crop.getY(),crop.getZ()+.5)>16) {
                var previous=NAVIGATION.put(v,new NavigationAttempt(crop,v.position()));
                boolean stalled=previous!=null && previous.target().equals(crop) && previous.position().distanceToSqr(v.position())<.25;
                boolean moved=move(v,crop);
                // Keep this target until reached. Five failures back off for thirty seconds, then retry a new cell.
                int failures=!moved || stalled?Math.min(5,p.navigationFailures()+1):0;
                if(failures>=5) { v.getNavigation().stop(); NAVIGATION.remove(v); }
                var scheduled=p.work(moved && failures<5?WorkState.WORKING:WorkState.BLOCKED,failures>=5?now+600:next,
                        failures<5?Math.floorMod(cursor-1,farm.cropVolume()):cursor,failures>=5?0:failures);
                data.replace(p,scheduled); return false;
            }
            var scheduled=p.work(WorkState.WORKING,next,cursor,0);
            NAVIGATION.remove(v);
            if(!data.replace(p,scheduled)) return false;
            if(!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                    || !net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,crop,v)) return false;
            var original=BlockSnapshot.create(level.dimension(),level,crop);
            if(!level.setBlock(crop,harvest.orElseThrow().replant(),3)) return false;
            var placement=new BlockEvent.EntityPlaceEvent(original,level.getBlockState(crop.below()),v);
            NeoForge.EVENT_BUS.post(placement);
            if(placement.isCanceled() || !level.getBlockState(crop).equals(harvest.orElseThrow().replant())) {
                original.restore(3); return false;
            }
            if(!data.harvest(scheduled,harvest.orElseThrow().food(),ProfessionConfig.XP_PER_HARVEST.get(),ProfessionConfig.LEVEL_CAP.get(),ProfessionConfig.XP_STEP.get())) {
                original.restore(3); return false;
            }
            v.swing(net.minecraft.world.InteractionHand.MAIN_HAND); return true;
        }
        data.replace(p,p.work(WorkState.IDLE,next,cursor,0)); return false;
    }
    private static boolean move(Villager v,BlockPos pos) {
        // Native local navigation; no teleport/fallback chunk tickets. Door support remains vanilla.
        v.getNavigation().setCanFloat(true);
        return v.getNavigation().moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,.65);
    }
    private static boolean loaded(ServerLevel level,BlockPos pos) { return level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4); }
    private static boolean loadedFarm(ServerLevel level,FunctionalBuilding farm) {
        // At most four chunks for a native module. Checking availability creates no chunk tickets.
        for(int x=farm.bounds().minX()>>4;x<=farm.bounds().maxX()>>4;x++)
            for(int z=farm.bounds().minZ()>>4;z<=farm.bounds().maxZ()>>4;z++)
                if(!level.getChunkSource().hasChunk(x,z)) return false;
        return true;
    }
    public static void onDeath(LivingDeathEvent event) {
        if(!event.isCanceled() && event.getEntity().level() instanceof ServerLevel level) retire(level,event.getEntity().getUUID());
    }
    public static void onConversion(LivingConversionEvent.Post event) {
        if(event.getEntity() instanceof Villager && !(event.getOutcome() instanceof Villager) && event.getEntity().level() instanceof ServerLevel level) retire(level,event.getEntity().getUUID());
    }
    private static void retire(ServerLevel level,UUID entity) {
        CitizenSavedData.get(level.getServer()).byEntity(entity).ifPresent(c -> ProfessionSavedData.get(level.getServer()).retire(c.id()));
    }
}

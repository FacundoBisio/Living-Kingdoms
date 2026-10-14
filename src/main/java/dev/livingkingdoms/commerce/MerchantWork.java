package dev.livingkingdoms.commerce;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.economy.KingdomEconomy;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.*;

/** Loaded citizen ticks only, one bounded physical station check per second, existing home/refuge routine. */
public final class MerchantWork {
    private static final Map<Villager,Boolean> CONTROLLED=new WeakHashMap<>();
    private MerchantWork() {}
    public static void register(IEventBus bus){bus.addListener(MerchantWork::onTick);bus.addListener(MerchantWork::onLeave);}
    public static Optional<Profession> profile(Villager worker) {
        if(!(worker.level() instanceof ServerLevel level) || !worker.isAlive() || worker.isBaby() || !worker.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY))return Optional.empty();
        var citizen=CitizenSavedData.get(level.getServer()).byEntity(worker.getUUID()).orElse(null);
        if(citizen==null || citizen.state()!=CitizenState.ACTIVE || citizen.homeId()==null || !CitizenService.canApply(citizen,worker))return Optional.empty();
        return ProfessionSavedData.get(level.getServer()).profession(citizen.id()).filter(p->p.active() && p.type()==ProfessionType.MERCHANT && p.settlementId().equals(citizen.settlementId()));
    }
    public static void control(Villager worker) {
        if(!(worker.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || profile(worker).isEmpty())return;
        if(CONTROLLED.putIfAbsent(worker,true)==null){worker.getBrain().stopAll(level,worker);worker.getBrain().removeAllBehaviors();worker.getNavigation().stop();}
        WorkDoors.ensure(worker);
    }
    public static void release(Villager worker) {
        if(CONTROLLED.remove(worker)==null)return;
        if(worker.level() instanceof ServerLevel level){worker.getNavigation().stop();worker.refreshBrain(level);}WorkDoors.release(worker);
    }
    public static void onLeave(EntityLeaveLevelEvent event){if(event.getEntity() instanceof Villager v && CONTROLLED.remove(v)!=null)WorkDoors.release(v);}
    public static void onTick(EntityTickEvent.Post event) {
        if(event.getEntity() instanceof Villager worker && worker.level() instanceof ServerLevel && Math.floorMod(worker.tickCount+worker.getId(),20)==0
                && worker.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY))tick(worker);
    }
    public static boolean tick(Villager worker) {
        if(!(worker.level() instanceof ServerLevel level))return false;
        var p=profile(worker).orElse(null);if(p==null){release(worker);return false;}
        var citizen=CitizenSavedData.get(level.getServer()).citizen(p.citizenId()).orElseThrow();var jobs=ProfessionSavedData.get(level.getServer());long now=KingdomEconomy.now(level.getServer());
        if(!ConvertedBuilderHomes.homeAvailable(level,citizen)) {
            if(ConvertedBuilderHomes.status(level,citizen)==ConvertedBuilderHomes.Status.INVALID)jobs.retire(citizen.id());
            else jobs.replace(p,p.work(WorkState.UNLOADED,now+100,0,0));release(worker);return false;
        }
        control(worker);if(CitizenRoutine.shelter(worker,citizen,p,now) || now<p.nextWorkAt())return false;
        if(worker.isNoAi() || worker.isPassenger() || worker.isLeashed() || worker.isTrading()){jobs.replace(p,p.work(WorkState.IDLE,now+100,0,0));return false;}
        var building=jobs.building(p.workplaceId()).filter(b->b.active() && b.supports(ProfessionType.MERCHANT)).orElse(null);
        if(building==null){jobs.retire(citizen.id());release(worker);return false;}
        if(!WorkSites.loaded(level,building)){jobs.replace(p,p.work(WorkState.UNLOADED,now+100,0,0));return false;}
        if(!stationPresent(level,building)){jobs.replace(p,p.work(WorkState.BLOCKED,now+200,0,0));return false;}
        var point=building.entrance().above();boolean arrived=worker.distanceToSqr(Vec3.atCenterOf(point))<=9;
        if(arrived)worker.getNavigation().stop();
        boolean moved=arrived || WorkSites.move(worker,point);int failures=moved?0:Math.min(5,p.navigationFailures()+1);
        jobs.replace(p,p.work(moved?WorkState.WORKING:WorkState.BLOCKED,now+(failures>=5?600:40),0,failures));return arrived;
    }
    public static boolean stationPresent(ServerLevel level,FunctionalBuilding market) {
        if(!market.active() || !market.supports(ProfessionType.MERCHANT) || !WorkSites.loaded(level,market))return false;
        var module=WorkSites.resolve(level,market).orElse(null);if(module==null)return false;
        var barrel=module.worldPosition(new BlockPos(2,1,2),market.origin(),market.rotation());
        var lectern=module.worldPosition(new BlockPos(2,1,module.size().getZ()-3),market.origin(),market.rotation());
        return level.getBlockState(barrel).is(Blocks.BARREL) && level.getBlockState(lectern).is(Blocks.LECTERN);
    }
    public static boolean available(Villager worker) {
        var p=profile(worker).orElse(null);if(p==null || !(worker.level() instanceof ServerLevel level) || !CitizenRoutine.workingHours(level)
                || worker.isNoAi() || worker.isPassenger() || worker.isLeashed() || worker.isSleeping() || worker.isTrading())return false;
        var citizen=CitizenSavedData.get(level.getServer()).citizen(p.citizenId()).orElseThrow();
        if(CitizenRoutine.threatened(worker,citizen) || !ConvertedBuilderHomes.homeAvailable(level,citizen))return false;
        var building=ProfessionSavedData.get(level.getServer()).building(p.workplaceId()).orElse(null);
        return building!=null && stationPresent(level,building) && worker.distanceToSqr(Vec3.atCenterOf(building.entrance().above()))<=9;
    }
}

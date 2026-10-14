package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.EconomyConfig;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.*;

/** Native loaded workers, physical station validation, persistent cooldowns and stock receipts. No offline catch-up. */
public final class EconomicWork {
    private static final Map<Villager,Boolean> CONTROLLED=new WeakHashMap<>();
    private static final String TOOL="livingkingdoms:economic_tool",PICKUP="livingkingdoms:economic_original_pickup";
    private EconomicWork() {}
    public static Optional<Profession> profile(Villager worker) {
        if(!(worker.level() instanceof ServerLevel level) || !worker.isAlive() || worker.isBaby() || !worker.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) return Optional.empty();
        var citizen=CitizenSavedData.get(level.getServer()).byEntity(worker.getUUID()).orElse(null);
        if(citizen==null || citizen.state()!=CitizenState.ACTIVE || citizen.homeId()==null || !CitizenService.canApply(citizen,worker)) return Optional.empty();
        return ProfessionSavedData.get(level.getServer()).profession(citizen.id()).filter(p->p.active() && p.type().economic() && p.settlementId().equals(citizen.settlementId()));
    }
    public static boolean assigned(Villager worker) {return profile(worker).isPresent();}
    public static void control(Villager worker) {
        if(!(worker.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !assigned(worker)) return;
        var p=profile(worker).orElseThrow();var s=SettlementSavedData.get(level.getServer()).get(p.settlementId()).orElseThrow();
        worker.restrictTo(new BlockPos(s.territory().x(),s.territory().y(),s.territory().z()),s.territory().radius());
        if(CONTROLLED.putIfAbsent(worker,Boolean.TRUE)==null) {worker.getBrain().stopAll(level,worker);worker.getBrain().removeAllBehaviors();worker.getNavigation().stop();}
        WorkDoors.ensure(worker);
        if(!worker.getPersistentData().contains(PICKUP)) worker.getPersistentData().putBoolean(PICKUP,worker.canPickUpLoot());worker.setCanPickUpLoot(false);
        if(worker.getMainHandItem().isEmpty()) {
            var item=switch(p.type()) {case LUMBERJACK->Items.IRON_AXE;case MINER->Items.IRON_PICKAXE;default->Items.IRON_HOE;};
            var stack=new ItemStack(item);var tag=new CompoundTag();tag.putUUID(TOOL,p.citizenId());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
            worker.setItemSlot(EquipmentSlot.MAINHAND,stack);worker.setDropChance(EquipmentSlot.MAINHAND,0);
        }
    }
    public static void release(Villager worker) {
        boolean controlled=CONTROLLED.remove(worker)!=null;
        if(!controlled && !worker.getPersistentData().contains(PICKUP)) return;
        if(controlled && worker.level() instanceof ServerLevel level) {worker.getNavigation().stop();worker.refreshBrain(level);}
        WorkDoors.release(worker);
        if(worker.getMainHandItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().hasUUID(TOOL)) {
            worker.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);worker.setDropChance(EquipmentSlot.MAINHAND,net.minecraft.world.entity.Mob.DEFAULT_EQUIPMENT_DROP_CHANCE);
        }
        if(worker.getPersistentData().contains(PICKUP)) {worker.setCanPickUpLoot(worker.getPersistentData().getBoolean(PICKUP));worker.getPersistentData().remove(PICKUP);}
    }
    public static void onJoin(EntityJoinLevelEvent event) {if(event.getEntity() instanceof Villager v && event.getLevel() instanceof ServerLevel && assigned(v)) control(v);}
    public static void onLeave(EntityLeaveLevelEvent event) {if(event.getEntity() instanceof Villager v && CONTROLLED.remove(v)!=null) WorkDoors.release(v);}
    public static void onTick(EntityTickEvent.Post event) {
        if(event.getEntity() instanceof Villager worker && worker.level() instanceof ServerLevel && Math.floorMod(worker.tickCount+worker.getId(),20)==0
                && worker.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) tick(worker);
    }
    public static boolean tick(Villager worker) {
        if(!(worker.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        var p=profile(worker).orElse(null);if(p==null) {release(worker);return false;}
        var people=CitizenSavedData.get(level.getServer());var citizen=people.citizen(p.citizenId()).orElseThrow();var jobs=ProfessionSavedData.get(level.getServer());long now=KingdomEconomy.now(level.getServer());
        if(!ConvertedBuilderHomes.homeAvailable(level,citizen)) {
            if(ConvertedBuilderHomes.status(level,citizen)==ConvertedBuilderHomes.Status.INVALID) jobs.retire(citizen.id());
            else jobs.replace(p,p.work(WorkState.UNLOADED,Math.max(p.nextWorkAt(),now+100),0,0));release(worker);return false;
        }
        control(worker);
        if(CitizenRoutine.shelter(worker,citizen,p,now)) return false;
        if(now<p.nextWorkAt()) return false;
        if(worker.isNoAi() || worker.isPassenger() || worker.isLeashed() || worker.isTrading()) {jobs.replace(p,p.work(WorkState.IDLE,now+100,0,0));return false;}
        var workplace=jobs.building(p.workplaceId()).filter(b->b.active() && b.supports(p.type())).orElse(null);
        if(workplace==null) {jobs.retire(citizen.id());release(worker);return false;}
        if(!WorkSites.loaded(level,workplace)) {jobs.replace(p,p.work(WorkState.UNLOADED,now+100,0,0));return false;}
        if(!stationPresent(level,workplace,p.type())) {jobs.replace(p,p.work(WorkState.BLOCKED,now+200,0,0));return false;}
        var point=workplace.entrance().above();
        if(worker.distanceToSqr(point.getX()+.5,point.getY(),point.getZ()+.5)>9) {
            boolean moved=WorkSites.move(worker,point);int failures=moved?0:Math.min(5,p.navigationFailures()+1);
            jobs.replace(p,p.work(moved?WorkState.WORKING:WorkState.BLOCKED,now+(failures>=5?600:40),0,failures));return false;
        }
        worker.getNavigation().stop();var account=KingdomEconomy.ensure(level.getServer(),p.settlementId());var recipe=EconomicRecipe.forProfession(p.type(),p.level().value());
        if(recipe.outputs().entrySet().stream().anyMatch(output->account.room(output.getKey())<output.getValue())) {jobs.replace(p,p.work(WorkState.STORAGE_FULL,now+100,0,0));return false;}
        var transfer=KingdomEconomy.plan(level.getServer(),p.settlementId(),recipe.inputs(),recipe.outputs(),true);
        if(transfer.isEmpty()) {jobs.replace(p,p.work(WorkState.MISSING_INPUTS,now+100,0,0));return false;}
        int cooldown=EconomicRecipe.cooldown(EconomyConfig.PRODUCTION_INTERVAL.get(),p.level().value(),BuildingDevelopment.level(level.getServer(),p.settlementId(),workplace.id()));
        var scheduled=p.work(WorkState.WORKING,now+cooldown,0,0);
        if(!KingdomEconomy.valid(level.getServer(),transfer.get()) || !jobs.replace(p,scheduled)) return false;
        if(!KingdomEconomy.commit(level.getServer(),transfer.get())) throw new IllegalStateException("Production changed within server-thread batch");
        jobs.economicExperience(scheduled,5,ProfessionConfig.LEVEL_CAP.get(),ProfessionConfig.XP_STEP.get());worker.swing(InteractionHand.MAIN_HAND);return true;
    }
    public static boolean stationPresent(ServerLevel level,FunctionalBuilding workplace,ProfessionType type) {
        var resolved=WorkSites.resolve(level,workplace);if(resolved.isEmpty()) return false;var module=resolved.orElseThrow();
        var local=switch(type) {case LUMBERJACK,MINER->new BlockPos(2,1,module.size().getZ()-3);case BLACKSMITH->new BlockPos(2,1,2);default->null;};
        if(local==null) return false;var pos=module.worldPosition(local,workplace.origin(),workplace.rotation());if(!WorkSites.loaded(level,pos)) return false;
        return level.getBlockState(pos).is(switch(type) {case LUMBERJACK->Blocks.CRAFTING_TABLE;case MINER->Blocks.STONECUTTER;default->Blocks.SMITHING_TABLE;});
    }
    public static boolean repairHeld(ServerPlayer player,UUID settlement) {
        if(!KingdomEconomy.authorized(player,settlement) || !CitizenRoutine.workingHours(player.serverLevel())) return false;
        var item=player.getMainHandItem();if(!item.isDamaged() || !(item.getItem() instanceof TieredItem || item.getItem() instanceof ArmorItem || item.getItem() instanceof ShieldItem)) return false;
        var jobs=ProfessionSavedData.get(player.server);long now=KingdomEconomy.now(player.server);
        for(var p:jobs.professions(settlement)) if(p.active() && p.type()==ProfessionType.BLACKSMITH && now>=p.nextWorkAt()) {
            var citizen=CitizenSavedData.get(player.server).citizen(p.citizenId()).orElse(null);if(citizen==null) continue;
            if(!(player.serverLevel().getEntity(citizen.entityId()) instanceof Villager worker) || !assigned(worker) || CitizenRoutine.threatened(worker,citizen)) continue;
            if(worker.isNoAi() || worker.isPassenger() || worker.isLeashed() || worker.isTrading() || worker.isSleeping()
                    || !ConvertedBuilderHomes.homeAvailable(player.serverLevel(),citizen)) continue;
            var workplace=jobs.building(p.workplaceId()).orElse(null);
            if(workplace==null || !WorkSites.loaded(player.serverLevel(),workplace) || !stationPresent(player.serverLevel(),workplace,p.type())
                    || worker.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(workplace.entrance().above()))>9) continue;
            var transfer=KingdomEconomy.plan(player.server,settlement,Map.of(StockResource.IRON,1,StockResource.FOOD,1),Map.of(),false);if(transfer.isEmpty()) return false;
            var scheduled=p.work(WorkState.REPAIRING,now+EconomyConfig.PRODUCTION_INTERVAL.get(),0,0);
            if(!KingdomEconomy.valid(player.server,transfer.get()) || !jobs.replace(p,scheduled)) return false;
            if(!KingdomEconomy.commit(player.server,transfer.get())) throw new IllegalStateException("Forge repair changed during commit");
            item.setDamageValue(Math.max(0,item.getDamageValue()-32-p.level().value()*8));player.getInventory().setChanged();
            jobs.economicExperience(scheduled,5,ProfessionConfig.LEVEL_CAP.get(),ProfessionConfig.XP_STEP.get());worker.swing(InteractionHand.MAIN_HAND);KingdomEconomy.sync(player);return true;
        }
        return false;
    }
}

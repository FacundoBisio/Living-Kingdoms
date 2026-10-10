package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.npc.Villager;
import java.util.*;

/** Local metadata synchronization/assignment boundary used by GUI and future profession handlers. */
public final class ProfessionService {
    private ProfessionService() {}
    public static void ensure(ServerLevel level,Settlement settlement) {
        CitizenService.ensureInitialized(level,settlement);
        var data=ProfessionSavedData.get(level.getServer()); var citizens=CitizenSavedData.get(level.getServer());
        var builders=data.professions(settlement.id()).stream().filter(p -> p.active() && p.type()==ProfessionType.BUILDER).toList();
        data.synchronize(settlement,SettlementSavedData.get(level.getServer()).layout(settlement.id()).orElse(null),
                ProfessionConfig.FARM_SLOTS.get(),dev.livingkingdoms.config.GuardConfig.SLOTS.get(),dev.livingkingdoms.config.BuilderConfig.SLOTS.get(),citizens.citizens(settlement.id()));
        for(var builder:builders) if(data.profession(builder.citizenId()).filter(Profession::active).isEmpty())
            dev.livingkingdoms.construction.ConstructionService.releaseBuilder(level.getServer(),builder.citizenId());
        data.ensureFood(settlement.id(),ProfessionConfig.FOOD_CAPACITY.get());
    }
    public static FoodStock food(net.minecraft.server.MinecraftServer server,UUID settlement) {
        return ProfessionSavedData.get(server).food(settlement,ProfessionConfig.FOOD_CAPACITY.get());
    }
    public static double immigrationModifier(net.minecraft.server.MinecraftServer server,UUID settlement) {
        int low=ProfessionConfig.LOW_FOOD.get(), healthy=Math.max(low+1,ProfessionConfig.HEALTHY_FOOD.get());
        return FarmerProgression.foodModifier(food(server,settlement).stock(),low,healthy,ProfessionConfig.LOW_FOOD_MODIFIER.get());
    }
    public static Map<ResourceKind,Integer> shortageWeights(net.minecraft.server.MinecraftServer server,UUID settlement) {
        // Existing abstract quest fixtures/metadata without functional buildings retain their old neutral policy.
        var data=ProfessionSavedData.get(server);
        var weights=new EnumMap<ResourceKind,Integer>(ResourceKind.class);
        if(!data.buildings(settlement).isEmpty() && food(server,settlement).stock()<ProfessionConfig.LOW_FOOD.get()) weights.put(ResourceKind.WHEAT,12);
        dev.livingkingdoms.construction.ConstructionService.shortageWeights(server,settlement).forEach((kind,weight) -> weights.merge(kind,weight,Math::max));
        return Map.copyOf(weights);
    }
    public static boolean assign(ServerPlayer player,UUID settlementId,UUID citizenId) { return assign(player,settlementId,citizenId,ProfessionType.FARMER); }
    public static boolean assignGuard(ServerPlayer player,UUID settlementId,UUID citizenId) { return assign(player,settlementId,citizenId,ProfessionType.GUARD); }
    public static boolean assignBuilder(ServerPlayer player,UUID settlementId,UUID citizenId) { return assign(player,settlementId,citizenId,ProfessionType.BUILDER); }
    private static boolean assign(ServerPlayer player,UUID settlementId,UUID citizenId,ProfessionType type) {
        var settlement=authorized(player,settlementId); if(settlement==null) return false;
        ensure(player.serverLevel(),settlement);
        var people=CitizenSavedData.get(player.server); var citizen=people.citizen(citizenId).filter(c -> c.settlementId().equals(settlementId)).orElse(null);
        if(citizen==null) return false;
        var loaded=player.serverLevel().getEntity(citizen.entityId());
        if(loaded!=null && (!(loaded instanceof Villager v) || !CitizenService.canApply(citizen,v))) return false;
        var data=ProfessionSavedData.get(player.server);
        if(type==ProfessionType.BUILDER) {
            var old=data.profession(citizenId).orElseThrow();
            if(old.type()!=ProfessionType.UNASSIGNED || old.active() || citizen.state()!=CitizenState.ACTIVE || citizen.role()==CitizenRole.MAYOR
                    || data.buildings(settlementId).stream().noneMatch(b -> b.active() && b.supports(ProfessionType.BUILDER) && data.workers(b.id())<b.workplaceSlots())) return false;
            if(citizen.homeId()==null) citizen=ConvertedBuilderHomes.ensure(player.serverLevel(),citizen).orElse(null);
            if(citizen==null || ConvertedBuilderHomes.status(player.serverLevel(),citizen)==ConvertedBuilderHomes.Status.INVALID) return false;
        }
        for(var b:data.buildings(settlementId)) if(b.supports(type) && switch(type) {
            case FARMER -> data.assignFarmer(citizen,b.id(),ImmigrationService.now(player.server));
            case GUARD -> data.assignGuard(citizen,b.id(),ImmigrationService.now(player.server));
            case BUILDER -> data.assignBuilder(citizen,b.id(),ImmigrationService.now(player.server));
            default -> false;
        }) {
            if(type==ProfessionType.BUILDER) dev.livingkingdoms.construction.ConstructionService.assignReady(player.server,settlementId);
            if(player.serverLevel().getEntity(citizen.entityId()) instanceof Villager villager) {
                switch(type) { case FARMER -> FarmerWork.control(villager); case GUARD -> GuardWork.control(villager); case BUILDER -> BuilderWork.control(villager); default -> {} }
            }
            if(type==ProfessionType.GUARD) dev.livingkingdoms.advancement.KingdomMilestone.awardFirstGuard(player);
            if(type==ProfessionType.BUILDER) dev.livingkingdoms.advancement.KingdomMilestone.awardFirstBuilder(player);
            dev.livingkingdoms.advancement.KingdomMilestone.awardFirstProfession(player); return true;
        }
        return false;
    }
    public static boolean remove(ServerPlayer player,UUID settlementId,UUID citizenId) {
        if(authorized(player,settlementId)==null) return false;
        var citizen=CitizenSavedData.get(player.server).citizen(citizenId).filter(c -> c.settlementId().equals(settlementId) && c.state()==CitizenState.ACTIVE).orElse(null);
        if(citizen==null || !(ProfessionSavedData.get(player.server).removeFarmer(citizenId) || ProfessionSavedData.get(player.server).removeGuard(citizenId)
                || ProfessionSavedData.get(player.server).removeBuilder(citizenId))) return false;
        dev.livingkingdoms.construction.ConstructionService.releaseBuilder(player.server,citizenId);
        if(player.serverLevel().getEntity(citizen.entityId()) instanceof Villager villager) {
            FarmerWork.release(villager); GuardWork.release(villager); BuilderWork.release(villager); CitizenService.apply(citizen,villager);
        }
        return true;
    }
    public static boolean canAssign(Citizen citizen,Profession p,List<FunctionalBuilding> buildings,ProfessionSavedData data) {
        return canAssign(citizen,p,buildings,data,ProfessionType.FARMER);
    }
    public static boolean canAssign(Citizen citizen,Profession p,List<FunctionalBuilding> buildings,ProfessionSavedData data,ProfessionType type) {
        return citizen.state()==CitizenState.ACTIVE && citizen.homeId()!=null && citizen.role()!=CitizenRole.MAYOR
                && (type!=ProfessionType.GUARD && type!=ProfessionType.BUILDER || p.type()==ProfessionType.UNASSIGNED)
                && !(p.active() && p.type()!=ProfessionType.UNASSIGNED) && buildings.stream().anyMatch(b ->
                b.active() && b.supports(type) && b.settlementId().equals(citizen.settlementId()) && data.workers(b.id())<b.workplaceSlots());
    }
    public static boolean canAssignBuilder(ServerLevel level,Citizen citizen,Profession p,List<FunctionalBuilding> buildings,ProfessionSavedData data) {
        if(citizen.homeId()!=null) return ConvertedBuilderHomes.status(level,citizen)!=ConvertedBuilderHomes.Status.INVALID
                && canAssign(citizen,p,buildings,data,ProfessionType.BUILDER);
        return citizen.state()==CitizenState.ACTIVE && citizen.role()!=CitizenRole.MAYOR && p.type()==ProfessionType.UNASSIGNED && !p.active()
                && p.citizenId().equals(citizen.id()) && p.settlementId().equals(citizen.settlementId())
                && buildings.stream().anyMatch(b -> b.active() && b.supports(ProfessionType.BUILDER) && b.settlementId().equals(citizen.settlementId()) && data.workers(b.id())<b.workplaceSlots())
                && ConvertedBuilderHomes.eligible(level,citizen);
    }
    private static Settlement authorized(ServerPlayer player,UUID id) {
        if(!player.server.isSameThread()) throw new IllegalStateException("Assignment requires server thread");
        var s=SettlementSavedData.get(player.server).get(id).orElse(null);
        return s!=null && s.faction().isAllied() && s.lifecycle()==SettlementLifecycle.ESTABLISHED && player.isAlive()
                && !player.isSpectator() && player.mayBuild() && s.territory().contains(player.serverLevel().dimension().location().toString(),
                player.blockPosition().getX(),player.blockPosition().getZ()) ? s : null;
    }
}

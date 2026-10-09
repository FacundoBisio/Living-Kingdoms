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
        data.synchronize(settlement,SettlementSavedData.get(level.getServer()).layout(settlement.id()).orElse(null),
                ProfessionConfig.FARM_SLOTS.get(),dev.livingkingdoms.config.GuardConfig.SLOTS.get(),citizens.citizens(settlement.id()));
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
        return !data.buildings(settlement).isEmpty() && food(server,settlement).stock()<ProfessionConfig.LOW_FOOD.get()
                ? Map.of(ResourceKind.WHEAT,12) : Map.of();
    }
    public static boolean assign(ServerPlayer player,UUID settlementId,UUID citizenId) { return assign(player,settlementId,citizenId,ProfessionType.FARMER); }
    public static boolean assignGuard(ServerPlayer player,UUID settlementId,UUID citizenId) { return assign(player,settlementId,citizenId,ProfessionType.GUARD); }
    private static boolean assign(ServerPlayer player,UUID settlementId,UUID citizenId,ProfessionType type) {
        var settlement=authorized(player,settlementId); if(settlement==null) return false;
        ensure(player.serverLevel(),settlement);
        var people=CitizenSavedData.get(player.server); var citizen=people.citizen(citizenId).filter(c -> c.settlementId().equals(settlementId)).orElse(null);
        if(citizen==null) return false;
        var loaded=player.serverLevel().getEntity(citizen.entityId());
        if(loaded!=null && (!(loaded instanceof Villager v) || !CitizenService.canApply(citizen,v))) return false;
        var data=ProfessionSavedData.get(player.server);
        for(var b:data.buildings(settlementId)) if(b.kind()==(type==ProfessionType.FARMER?dev.livingkingdoms.structure.BuildingKind.FARM:dev.livingkingdoms.structure.BuildingKind.BARRACKS)
                && (type==ProfessionType.FARMER?data.assignFarmer(citizen,b.id(),ImmigrationService.now(player.server)):data.assignGuard(citizen,b.id(),ImmigrationService.now(player.server)))) {
            if(player.serverLevel().getEntity(citizen.entityId()) instanceof Villager villager) {
                if(type==ProfessionType.FARMER) FarmerWork.control(villager); else GuardWork.control(villager);
            }
            if(type==ProfessionType.GUARD) dev.livingkingdoms.advancement.KingdomMilestone.awardFirstGuard(player);
            dev.livingkingdoms.advancement.KingdomMilestone.awardFirstProfession(player); return true;
        }
        return false;
    }
    public static boolean remove(ServerPlayer player,UUID settlementId,UUID citizenId) {
        if(authorized(player,settlementId)==null) return false;
        var citizen=CitizenSavedData.get(player.server).citizen(citizenId).filter(c -> c.settlementId().equals(settlementId) && c.state()==CitizenState.ACTIVE).orElse(null);
        if(citizen==null || !(ProfessionSavedData.get(player.server).removeFarmer(citizenId) || ProfessionSavedData.get(player.server).removeGuard(citizenId))) return false;
        if(player.serverLevel().getEntity(citizen.entityId()) instanceof Villager villager) {
            FarmerWork.release(villager); GuardWork.release(villager); CitizenService.apply(citizen,villager);
        }
        return true;
    }
    public static boolean canAssign(Citizen citizen,Profession p,List<FunctionalBuilding> buildings,ProfessionSavedData data) {
        return canAssign(citizen,p,buildings,data,ProfessionType.FARMER);
    }
    public static boolean canAssign(Citizen citizen,Profession p,List<FunctionalBuilding> buildings,ProfessionSavedData data,ProfessionType type) {
        return citizen.state()==CitizenState.ACTIVE && citizen.homeId()!=null && citizen.role()!=CitizenRole.MAYOR
                && (type!=ProfessionType.GUARD || p.type()==ProfessionType.UNASSIGNED)
                && !(p.active() && p.type()!=ProfessionType.UNASSIGNED) && buildings.stream().anyMatch(b ->
                b.active() && b.kind()==(type==ProfessionType.FARMER?dev.livingkingdoms.structure.BuildingKind.FARM:dev.livingkingdoms.structure.BuildingKind.BARRACKS) && data.workers(b.id())<b.workplaceSlots());
    }
    private static Settlement authorized(ServerPlayer player,UUID id) {
        if(!player.server.isSameThread()) throw new IllegalStateException("Assignment requires server thread");
        var s=SettlementSavedData.get(player.server).get(id).orElse(null);
        return s!=null && s.faction().isAllied() && s.lifecycle()==SettlementLifecycle.ESTABLISHED && player.isAlive()
                && !player.isSpectator() && player.mayBuild() && s.territory().contains(player.serverLevel().dimension().location().toString(),
                player.blockPosition().getX(),player.blockPosition().getZ()) ? s : null;
    }
}

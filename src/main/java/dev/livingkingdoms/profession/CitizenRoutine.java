package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.domain.Citizen;
import dev.livingkingdoms.citizen.domain.Housing;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.SettlementSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import java.util.*;

/** Shared rest/refuge policy. Searches only the citizen's loaded home, once per bounded retry. */
public final class CitizenRoutine {
    private record Beds(UUID home,List<BlockPos> positions,long expires) {}
    private static final Map<Villager,Beds> BEDS=new WeakHashMap<>();
    private CitizenRoutine() {}
    public static boolean workingHours(ServerLevel level) {long time=Math.floorMod(level.getDayTime(),24000);return time>=1000 && time<11000;}
    public static boolean threatened(Villager worker,Citizen citizen) {
        return worker.level() instanceof ServerLevel level && (DefenseService.status(level.getServer(),citizen.settlementId())==SettlementSafety.THREATENED || BuilderWork.inDanger(worker)
                || dev.livingkingdoms.rpg.AlarmService.active(level.getServer(),citizen.settlementId()));
    }
    public static boolean shelter(Villager worker,Citizen citizen,Profession profession,long now) {
        if(!(worker.level() instanceof ServerLevel level) || citizen.role()==CitizenRole.MAYOR) return false;
        if(dev.livingkingdoms.rpg.RallyService.steer(worker,citizen)) return true;
        // A brave resident works through alarms and threat alerts; an enemy next to them still sends them home.
        boolean threat=dev.livingkingdoms.rpg.domain.Trait.of(citizen.id())==dev.livingkingdoms.rpg.domain.Trait.BRAVE?BuilderWork.inDanger(worker):threatened(worker,citizen);
        boolean working=profession!=null && profession.type()==ProfessionType.BUILDER?Math.floorMod(level.getDayTime(),24000)<12000:workingHours(level);
        if(!threat && working) {if(worker.isSleeping()) worker.stopSleeping();return false;}
        if(worker.isNoAi() || worker.isPassenger() || worker.isLeashed() || worker.isTrading()) return false;
        if(threat && worker.isSleeping()) worker.stopSleeping();
        var home=citizen.homeId()==null?null:CitizenSavedData.get(level.getServer()).housing(citizen.homeId()).orElse(null);
        WorkState state=WorkState.UNLOADED;
        if(home!=null && WorkSites.loaded(level,home.entrance())) {
            BlockPos bed=bed(worker,home,now);
            var point=bed==null?home.entrance().above():bed;
            boolean arrived=worker.distanceToSqr(point.getX()+.5,point.getY(),point.getZ()+.5)<=4;
            if(!threat && arrived && bed!=null && !worker.isSleeping() && !level.getBlockState(bed).getValue(BedBlock.OCCUPIED)) worker.startSleeping(bed);
            if(worker.isSleeping()) state=WorkState.SLEEPING;
            else if(arrived) {worker.getNavigation().stop();state=threat?WorkState.FLEEING:WorkState.RESTING;}
            else {
                worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(point,.65F,1));
                state=WorkSites.move(worker,point)?threat?WorkState.FLEEING:WorkState.RETURNING_HOME:WorkState.BLOCKED;
            }
        } else worker.getNavigation().stop();
        if(profession!=null) {
            var next=profession.work(state,Math.max(profession.nextWorkAt(),now+40),profession.cropCursor(),profession.navigationFailures());
            if(!next.equals(profession)) ProfessionSavedData.get(level.getServer()).replace(profession,next);
        }
        return true;
    }
    private static BlockPos bed(Villager worker,Housing home,long now) {
        var level=(ServerLevel)worker.level();var cached=BEDS.get(worker);
        if(cached==null || !cached.home().equals(home.id()) || now>=cached.expires()) {
            var beds=new ArrayList<BlockPos>();
            if(home.convertedBed()) beds.add(home.position());
            else {
                var building=SettlementSavedData.get(level.getServer()).layout(home.settlementId()).stream().flatMap(layout->layout.buildings().stream())
                        .filter(b->Housing.identity(home.settlementId(),b).equals(home.id())).findFirst().orElse(null);
                // Only this home's persisted footprint; never choose another house's bed.
                if(building!=null) for(int x=building.bounds().minX();x<=building.bounds().maxX();x++) for(int z=building.bounds().minZ();z<=building.bounds().maxZ();z++) {
                    var pos=new BlockPos(x,home.position().getY()+1,z);if(!WorkSites.loaded(level,pos)) continue;
                    var state=level.getBlockState(pos);if(state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART)==BedPart.HEAD) beds.add(pos);
                }
            }
            // One bed per resident: housemates (sorted by id) take the home's beds in order, so nobody
            // hesitates between two beds or wanders to the camp's (0.34 playtest fix).
            if(beds.size()>1) {
                beds.sort(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
                var mates=CitizenSavedData.get(level.getServer()).citizens(home.settlementId()).stream()
                        .filter(c->home.id().equals(c.homeId()) && c.state()==dev.livingkingdoms.citizen.domain.CitizenState.ACTIVE).map(Citizen::id).sorted().toList();
                var self=CitizenSavedData.get(level.getServer()).byEntity(worker.getUUID()).map(Citizen::id).orElse(null);
                int index=Math.max(0,mates.indexOf(self));
                Collections.rotate(beds,-(index%beds.size()));
            }
            cached=new Beds(home.id(),List.copyOf(beds),now+(beds.isEmpty()?200:2400));BEDS.put(worker,cached);
            // Vanilla sleep behaviours follow the HOME memory: point it at this resident's own bed.
            if(!beds.isEmpty()) worker.getBrain().setMemory(MemoryModuleType.HOME,net.minecraft.core.GlobalPos.of(level.dimension(),beds.getFirst()));
        }
        for(var pos:cached.positions()) {
            if(!WorkSites.loaded(level,pos)) continue;var state=level.getBlockState(pos);
            if(state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART)==BedPart.HEAD && (!state.getValue(BedBlock.OCCUPIED) || worker.isSleeping())) return pos;
        }
        return null;
    }
}

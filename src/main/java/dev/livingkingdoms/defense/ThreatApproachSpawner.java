package dev.livingkingdoms.defense;

import dev.livingkingdoms.config.DefenseConfig;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.defense.domain.SettlementThreatEvent;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.Optional;

/** At most 24 loaded, terrain-validated proposals. A blocked annulus is refused, never loaded. */
public final class ThreatApproachSpawner {
    private ThreatApproachSpawner() {}
    public static Optional<SettlementThreatEvent> spawn(ServerLevel level,Settlement settlement,PartyType type,boolean rewardTest) {
        if(DefenseService.current(level.getServer(),settlement.id()).isPresent()) return Optional.empty();
        int min=Math.max(DefenseConfig.APPROACH_MIN_DISTANCE.get()+6,settlement.territory().radius()+7);
        int max=DefenseConfig.APPROACH_MAX_DISTANCE.get()-6;
        if(min>max) return Optional.empty();
        for(int radius:new int[]{min,(min+max)/2,max}) for(int i=0;i<8;i++) {
            double angle=i*Math.PI/4;
            var origin=new BlockPos(settlement.territory().x()+(int)Math.round(Math.cos(angle)*radius),settlement.territory().y(),
                    settlement.territory().z()+(int)Math.round(Math.sin(angle)*radius));
            if(!ThreatApproachPolicy.validOrigin(Math.hypot(origin.getX()-settlement.territory().x(),origin.getZ()-settlement.territory().z()),
                    settlement.territory().radius(),DefenseConfig.APPROACH_MIN_DISTANCE.get(),DefenseConfig.APPROACH_MAX_DISTANCE.get())
                    || SettlementSavedData.get(level.getServer()).nearAlliedTerritory(level.dimension().location().toString(),origin.getX(),origin.getZ(),6)
                    || !level.getChunkSource().hasChunk(origin.getX()>>4,origin.getZ()>>4)) continue;
            var result=EncounterSpawner.spawnAt(level,origin,type,true,rewardTest);
            if(!result.successful()) continue;
            var event=DefenseService.beginApproach(level,settlement,result.party());
            if(event.isPresent()) return event;
            dev.livingkingdoms.encounter.persistence.EncounterSavedData.get(level.getServer()).remove(result.party().id());
            for(var id:result.party().remainingMembers()) { var entity=level.getEntity(id); if(entity!=null) entity.discard(); }
            return Optional.empty();
        }
        return Optional.empty();
    }
}

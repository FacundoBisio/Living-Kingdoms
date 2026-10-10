package dev.livingkingdoms.profession;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.*;

/** Converts an existing citizen's real native bed into one explicit home; no survey, building, or block writes. */
public final class ConvertedBuilderHomes {
    public enum Status { VALID, UNLOADED, INVALID }
    private record Candidate(Housing home,boolean remembered) {}
    private ConvertedBuilderHomes() {}
    public static Status status(ServerLevel level,Citizen c) {
        if(c.homeId()==null) return Status.INVALID;
        var home=CitizenSavedData.get(level.getServer()).housing(c.homeId()).orElse(null);
        if(home==null || home.status()!=HousingStatus.ACTIVE) return Status.INVALID;
        if(!home.convertedBed()) return Status.VALID;
        if(!home.dimension().equals(level.dimension().location().toString())) return Status.INVALID;
        if(!loaded(level,home.position())) return Status.UNLOADED;
        var state=level.getBlockState(home.position());
        if(!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART)!=BedPart.HEAD) return Status.INVALID;
        var foot=home.position().relative(state.getValue(BedBlock.FACING).getOpposite());
        if(!loaded(level,foot)) return Status.UNLOADED;
        return completeBed(level,home.position())?Status.VALID:Status.INVALID;
    }
    public static boolean homeAvailable(ServerLevel level,Citizen citizen) { return status(level,citizen)==Status.VALID; }
    public static boolean eligible(ServerLevel level,Citizen c) { return candidate(level,c).isPresent(); }
    public static Optional<Citizen> ensure(ServerLevel level,Citizen c) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Converted homes require server thread");
        var candidate=candidate(level,c).orElse(null); if(candidate==null) return Optional.empty();
        var home=candidate.home(); var pos=home.position(); boolean acquired=false;
        // A remembered HOME already owns its native ticket. A fallback claims only a free bed,
        // with every POI query chunk checked beforehand because PoiManager.take reads a 3x3 square.
        boolean free=level.getPoiManager().getInChunk(type -> type.is(PoiTypes.HOME),new ChunkPos(pos),PoiManager.Occupancy.HAS_SPACE)
                .anyMatch(record -> record.getPos().equals(pos));
        if(free) {
            if(!neighborsLoaded(level,pos)) return Optional.empty();
            acquired=level.getPoiManager().take(type -> type.is(PoiTypes.HOME),(type,p) -> p.equals(pos),pos,0).isPresent();
            if(!acquired) return Optional.empty();
        } else if(!candidate.remembered()) return Optional.empty();
        var settlement=SettlementSavedData.get(level.getServer()).get(c.settlementId()).orElseThrow(); var people=CitizenSavedData.get(level.getServer());
        if(!people.assignConvertedBedHome(settlement,c,home)) { if(acquired) level.getPoiManager().release(pos); return Optional.empty(); }
        var updated=people.citizen(c.id()).orElseThrow();
        if(level.getEntity(c.entityId()) instanceof Villager v) { v.getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),pos)); CitizenService.apply(updated,v); }
        return Optional.of(updated);
    }
    private static Optional<Candidate> candidate(ServerLevel level,Citizen c) {
        var s=SettlementSavedData.get(level.getServer()).get(c.settlementId()).orElse(null);
        if(s==null || s.provenance().origin()!=SettlementOrigin.CONVERTED || c.homeId()!=null || c.state()!=CitizenState.ACTIVE || c.role()==CitizenRole.MAYOR
                || !(level.getEntity(c.entityId()) instanceof Villager v) || !CitizenService.canApply(c,v)) return Optional.empty();
        var remembered=v.getBrain().getMemory(MemoryModuleType.HOME).filter(g -> g.dimension().equals(level.dimension()));
        if(remembered.isPresent()) {
            var home=home(level,s,remembered.get().pos()); if(home.isPresent()) return Optional.of(new Candidate(home.get(),true));
        }
        int radius=16; var center=v.blockPosition(); var choices=new ArrayList<Housing>();
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) {
            if(!level.getChunkSource().hasChunk(x,z)) continue;
            level.getPoiManager().getInChunk(type -> type.is(PoiTypes.HOME),new ChunkPos(x,z),PoiManager.Occupancy.HAS_SPACE).forEach(record -> {
                var pos=record.getPos(); if(pos.distSqr(center)<=radius*radius && neighborsLoaded(level,pos)) home(level,s,pos).ifPresent(choices::add);
            });
        }
        return choices.stream().min(Comparator.comparingDouble((Housing h) -> h.position().distSqr(center)).thenComparing(h -> h.id().toString()))
                .map(h -> new Candidate(h,false));
    }
    private static Optional<Housing> home(ServerLevel level,Settlement s,BlockPos head) {
        if(!loaded(level,head) || !s.territory().contains(level.dimension().location().toString(),head.getX(),head.getZ()) || !completeBed(level,head)) return Optional.empty();
        var state=level.getBlockState(head); var foot=head.relative(state.getValue(BedBlock.FACING).getOpposite());
        if(!s.territory().contains(level.dimension().location().toString(),foot.getX(),foot.getZ())) return Optional.empty();
        UUID id=Housing.convertedBedIdentity(s.id(),head);
        if(CitizenSavedData.get(level.getServer()).occupancy(id)>0) return Optional.empty();
        var entrance=head.relative(state.getValue(BedBlock.FACING).getClockWise());
        if(!s.territory().contains(level.dimension().location().toString(),entrance.getX(),entrance.getZ())) entrance=foot;
        return Optional.of(new Housing(id,s.id(),level.dimension().location().toString(),Housing.CONVERTED_BED_TEMPLATE,head,entrance,1,HousingStatus.ACTIVE));
    }
    private static boolean completeBed(ServerLevel level,BlockPos head) {
        if(!loaded(level,head)) return false; var state=level.getBlockState(head);
        if(!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART)!=BedPart.HEAD) return false;
        var foot=head.relative(state.getValue(BedBlock.FACING).getOpposite()); if(!loaded(level,foot)) return false; var other=level.getBlockState(foot);
        return other.is(state.getBlock()) && other.getValue(BedBlock.PART)==BedPart.FOOT && other.getValue(BedBlock.FACING)==state.getValue(BedBlock.FACING);
    }
    private static boolean loaded(ServerLevel level,BlockPos pos) { return level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4); }
    private static boolean neighborsLoaded(ServerLevel level,BlockPos pos) {
        for(int x=(pos.getX()>>4)-1;x<=(pos.getX()>>4)+1;x++) for(int z=(pos.getZ()>>4)-1;z<=(pos.getZ()>>4)+1;z++) if(!level.getChunkSource().hasChunk(x,z)) return false;
        return true;
    }
}

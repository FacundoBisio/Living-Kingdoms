package dev.livingkingdoms.profession.domain;

import dev.livingkingdoms.progression.domain.LevelValue;
import java.util.*;

/** Profession progress is separate from shared citizen levels and vanilla villager careers. */
public record Profession(UUID citizenId, UUID settlementId, ProfessionType type, LevelValue level,
        long experience, UUID workplaceId, boolean active, WorkState workState, long nextWorkAt,
        int cropCursor, int navigationFailures, Set<CitizenTrait> traits) {
    public Profession {
        Objects.requireNonNull(citizenId); Objects.requireNonNull(settlementId); Objects.requireNonNull(type);
        Objects.requireNonNull(level); Objects.requireNonNull(workState); traits=Set.copyOf(traits);
        if(experience<0 || experience>1_000_000_000L || nextWorkAt<0 || cropCursor<0 || cropCursor>675
                || navigationFailures<0 || navigationFailures>1000 || traits.size()>4
                || active && (type==ProfessionType.FARMER || type==ProfessionType.GUARD) && workplaceId==null
                || (type==ProfessionType.UNASSIGNED || type==ProfessionType.MAYOR) && workplaceId!=null)
            throw new IllegalArgumentException("Invalid profession state");
    }
    public static Profession initial(UUID citizen,UUID settlement,boolean mayor) {
        return new Profession(citizen,settlement,mayor?ProfessionType.MAYOR:ProfessionType.UNASSIGNED,
                new LevelValue(1),0,null,mayor,WorkState.IDLE,0,0,0,Set.of());
    }
    public Profession work(WorkState state,long next,int cursor,int failures) {
        return new Profession(citizenId,settlementId,type,level,experience,workplaceId,active,state,next,cursor,failures,traits);
    }
    public Profession retired() {
        return new Profession(citizenId,settlementId,type,level,experience,workplaceId,false,WorkState.IDLE,nextWorkAt,cropCursor,navigationFailures,traits);
    }
}

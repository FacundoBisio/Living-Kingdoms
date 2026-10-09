package dev.livingkingdoms.profession.domain;

import dev.livingkingdoms.faction.*;
import java.util.List;

/** Pure rules shared by native entity behavior, security consumers and tests. */
public final class GuardPolicy {
    private GuardPolicy() {}
    public static boolean hostile(Faction faction) { return FactionRelations.isHostile(Faction.ALLIED_KINGDOM,faction); }
    public static boolean within(double x,double z,double centerX,double centerZ,double range) {
        double dx=x-centerX,dz=z-centerZ; return dx*dx+dz*dz<=range*range;
    }
    public static int level(long xp,int cap,int step) { return FarmerProgression.level(xp,cap,step); }
    public static int equipmentTier(int level,int armorLevel) { return level>=armorLevel?2:1; }
    public static int security(List<Profession> jobs,List<FunctionalBuilding> buildings) {
        int score=0;
        for(var b:buildings) if(b.active()) score+=switch(b.kind()) {case BARRACKS -> 8; case WATCHTOWER -> 6; default -> 0;};
        for(var p:jobs) if(p.active() && p.type()==ProfessionType.GUARD) score+=12+Math.min(8,p.level().value()-1)*2;
        return Math.clamp(score,0,100);
    }
    public static double immigrationModifier(int security,double minimum) { return minimum+(1-minimum)*Math.clamp(security,0,60)/60.0; }
}

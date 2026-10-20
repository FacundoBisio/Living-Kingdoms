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
    public static final int BASE_ATTACK_COOLDOWN=25, MAX_ALERT_SEARCH_RADIUS=48, SHIELD_RANGE=4;
    /** Melee cadence in ticks: 25 at level 1, two ticks faster per level, never below the configured minimum. */
    public static int attackCooldown(int level,int minimum) {
        int floor=Math.clamp(minimum,1,BASE_ATTACK_COOLDOWN);
        return Math.max(floor,BASE_ATTACK_COOLDOWN-2*(Math.max(1,level)-1));
    }
    /**
     * Local hostile search radius. An early alert widens it by the multiplier, but never past 48 blocks or the
     * width of the defense area (targets outside the defense boundary are rejected anyway), nor below the base.
     */
    public static double searchRadius(double base,boolean alert,double multiplier,double defense) {
        if(!alert || multiplier<=1) return base;
        return Math.max(base,Math.min(Math.min(MAX_ALERT_SEARCH_RADIUS,2*defense),base*multiplier));
    }
    /** Shields are raised only while a hostile is close and the next swing is not ready yet. */
    public static boolean raiseShield(boolean enabled,boolean holdsShield,double distanceSqr,boolean attackReady) {
        return enabled && holdsShield && !attackReady && distanceSqr<=SHIELD_RANGE*SHIELD_RANGE;
    }
    public static int equipmentTier(int level,int armorLevel) { return level>=armorLevel?2:1; }
    public static int security(List<Profession> jobs,List<FunctionalBuilding> buildings) {
        int score=0;
        for(var b:buildings) if(b.active()) score+=switch(b.kind()) {case BARRACKS -> 8; case WATCHTOWER -> 6; default -> 0;};
        for(var p:jobs) if(p.active() && p.type()==ProfessionType.GUARD) score+=12+Math.min(8,p.level().value()-1)*2;
        return Math.clamp(score,0,100);
    }
    public static double immigrationModifier(int security,double minimum) { return minimum+(1-minimum)*Math.clamp(security,0,60)/60.0; }
}

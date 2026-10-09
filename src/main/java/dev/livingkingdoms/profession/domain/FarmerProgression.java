package dev.livingkingdoms.profession.domain;

/** Farmer policy only; future careers can interpret XP differently. */
public final class FarmerProgression {
    private FarmerProgression() {}
    public static int level(long xp,int cap,int step) {
        if(xp<0 || cap<1 || cap>100 || step<1) throw new IllegalArgumentException("Invalid Farmer progression");
        int level=1;
        while(level<cap && xp>=(long)step*level*(level+1)/2) level++;
        return level;
    }
    public static int cooldown(int base,int level,int cap) { return Math.max(20,(int)Math.round(base*(1-0.05*(Math.min(level,cap)-1)))); }
    public static int food(int base,double fraction) {
        if(base<1 || !Double.isFinite(fraction) || fraction<0 || fraction>1) throw new IllegalArgumentException("Invalid food conversion");
        return (int)Math.floor(base*fraction);
    }
    public static double foodModifier(int stock,int low,int healthy,double minimum) {
        if(stock<0 || low<0 || healthy<=low || minimum<0 || minimum>1) throw new IllegalArgumentException("Invalid food thresholds");
        return minimum+(1-minimum)*Math.clamp((double)(stock-low)/(healthy-low),0,1);
    }
}

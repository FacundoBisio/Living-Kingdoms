package dev.livingkingdoms.profession.domain;

/** Construction rewards are discrete milestone receipts; level bonuses remain small and bounded. */
public final class BuilderPolicy {
    private BuilderPolicy() {}
    public static int level(long xp,int cap,int step) {
        if(xp<0 || cap<1 || cap>100 || step<1) throw new IllegalArgumentException("Invalid Builder progression");
        int value=1;
        while(value<cap && xp>=(long)step*value*(value+1)/2) value++;
        return value;
    }
    public static double speedMultiplier(int level,int cap,double maximum) {
        if(level<1 || cap<1 || cap>100 || !Double.isFinite(maximum) || maximum<0 || maximum>.25)
            throw new IllegalArgumentException("Invalid Builder speed bonus");
        return 1+(cap==1?0:maximum*Math.clamp((double)(level-1)/(cap-1),0,1));
    }
}

package dev.livingkingdoms.defense;

/** Horizontal rules shared by spawning, objectives and regression tests. */
public final class ThreatApproachPolicy {
    private ThreatApproachPolicy() {}
    public static boolean validOrigin(double distance,int territoryRadius,int minimum,int maximum) {
        return minimum<=maximum && distance>=minimum && distance<=maximum && distance>territoryRadius+6;
    }
    public static boolean within(double x,double z,double centerX,double centerZ,double radius) {
        double dx=x-centerX,dz=z-centerZ;
        return dx*dx+dz*dz<=radius*radius;
    }
}

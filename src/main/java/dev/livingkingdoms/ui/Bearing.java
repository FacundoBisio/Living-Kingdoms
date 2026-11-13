package dev.livingkingdoms.ui;

/** Pure HUD math: 8-way arrow relative to where the player faces, and whole metres. Minecraft yaw: 0 = south (+Z), 90 = west (-X). */
public final class Bearing {
    public static final int HERE_METRES = 8;
    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
    private Bearing() {}

    /** dx/dz = target minus player. 0 = ahead, then clockwise (right of the player) in 45 degree steps. */
    public static int octant(double dx, double dz, double yawDegrees) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((target - yawDegrees) % 360 + 540) % 360 - 180; // wrapped to [-180,180)
        return (int) Math.floorMod(Math.round(relative / 45), 8L);
    }
    public static String arrow(double dx, double dz, double yawDegrees) { return ARROWS[octant(dx, dz, yawDegrees)]; }
    public static int metres(double dx, double dz) { return (int) Math.round(Math.sqrt(dx * dx + dz * dz)); }
    public static boolean here(double dx, double dz) { return dx * dx + dz * dz <= (double) HERE_METRES * HERE_METRES; }
}

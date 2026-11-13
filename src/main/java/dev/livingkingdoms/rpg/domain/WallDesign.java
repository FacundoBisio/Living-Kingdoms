package dev.livingkingdoms.rpg.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * How a kingdom's wall ring is laid out: 1–4 main gates facing cardinal directions (south first), optional corner towers
 * (four, on the diagonals) and optional gatehouses that turn every gate into a fortified entrance.
 */
public record WallDesign(int gates, boolean towers, boolean gatehouses) {
    public static final int MIN_GATES = 1, MAX_GATES = 4, TOWERS = 4, GATE_HALF_WIDTH = 1;
    public static final WallDesign DEFAULT = new WallDesign(2, false, false);
    public WallDesign {
        if (gates < MIN_GATES || gates > MAX_GATES) throw new IllegalArgumentException("Invalid gate count");
    }
    public WallDesign nextGates() { return new WallDesign(gates % MAX_GATES + 1, towers, gatehouses); }
    public WallDesign withTowers() { return new WallDesign(gates, true, gatehouses); }
    public WallDesign withGatehouses() { return new WallDesign(gates, towers, true); }

    /** Gate angles in radians (Minecraft: +Z is south = π/2, east = 0), always facing cardinal directions. */
    public List<Double> gateAngles() {
        double s = Math.PI / 2, n = 3 * Math.PI / 2, e = 0, w = Math.PI;
        return switch (gates) {
            case 1 -> List.of(s);
            case 2 -> List.of(s, n);
            case 3 -> List.of(s, e, w);
            default -> List.of(s, w, n, e);
        };
    }
    /** Tower angles: the four diagonals. */
    public static List<Double> towerAngles() {
        var out = new ArrayList<Double>();
        for (int k = 0; k < TOWERS; k++) out.add(Math.PI / 4 + Math.PI / 2 * k);
        return out;
    }
    /** Ring index closest to {@code angle} on a ring of the given offsets. */
    public static int nearest(List<int[]> ring, double angle) {
        int best = 0; double bestD = Double.MAX_VALUE;
        for (int i = 0; i < ring.size(); i++) {
            double a = Math.atan2(ring.get(i)[1], ring.get(i)[0]);
            double d = Math.abs(Math.atan2(Math.sin(a - angle), Math.cos(a - angle)));
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }
    /** Whether ring index {@code i} lies inside a gate opening (3 columns wide). */
    public boolean gate(List<int[]> ring, int i) {
        for (double angle : gateAngles()) {
            int g = nearest(ring, angle);
            int d = Math.floorMod(i - g, ring.size()); d = Math.min(d, ring.size() - d);
            if (d <= GATE_HALF_WIDTH) return true;
        }
        return false;
    }
}

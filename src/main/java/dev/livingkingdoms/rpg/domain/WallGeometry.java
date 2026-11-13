package dev.livingkingdoms.rpg.domain;

import java.util.*;

/**
 * World-free layout of a wall ring for one radius and design: ordered ring offsets, gate columns, gate and tower
 * centres, and which XZ offsets (relative to the territory centre) belong to the wall footprint. Instances are
 * immutable and shared through a small bounded cache, so callers never rebuild the ring per step.
 */
public final class WallGeometry {
    public static final int CACHE_SIZE = 32, GATEHOUSE_REACH = 3, TOWER_GATE_GAP = 5;
    private static final Map<Key, WallGeometry> CACHE = new LinkedHashMap<>(16, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, WallGeometry> eldest) { return size() > CACHE_SIZE; }
    };
    private record Key(int radius, WallDesign design) {}

    private final WallDesign design;
    private final List<int[]> ring;
    private final boolean[] gate;
    private final List<Integer> gateCenters, towerCenters;
    private final Set<Long> ringCells = new HashSet<>(), towerCells = new HashSet<>(), gatehouseCells = new HashSet<>();

    public static WallGeometry of(int radius, WallDesign design) {
        synchronized (CACHE) { return CACHE.computeIfAbsent(new Key(radius, design), k -> new WallGeometry(k.radius(), k.design())); }
    }
    public static int cacheSize() { synchronized (CACHE) { return CACHE.size(); } }

    public static long key(int dx, int dz) { return ((long) dx << 32) ^ (dz & 0xffffffffL); }

    private WallGeometry(int radius, WallDesign design) {
        this.design = design;
        ring = Collections.unmodifiableList(ring(radius));
        int n = ring.size();
        for (int[] o : ring) ringCells.add(key(o[0], o[1]));
        gate = new boolean[n];
        var gates = new ArrayList<Integer>();
        for (double angle : design.gateAngles()) {
            int g = WallDesign.nearest(ring, angle); gates.add(g);
            for (int d = -WallDesign.GATE_HALF_WIDTH; d <= WallDesign.GATE_HALF_WIDTH; d++) gate[Math.floorMod(g + d, n)] = true;
            for (int d = -GATEHOUSE_REACH; d <= GATEHOUSE_REACH; d++) if (d != 0) { int[] o = ring.get(Math.floorMod(g + d, n)); gatehouseCells.add(key(o[0], o[1])); }
        }
        gateCenters = List.copyOf(gates);
        var towers = new ArrayList<Integer>();
        for (double angle : WallDesign.towerAngles()) {
            int t = towerCenter(WallDesign.nearest(ring, angle));
            towers.add(t);
            int[] c = ring.get(t);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) towerCells.add(key(c[0] + dx, c[1] + dz));
        }
        towerCenters = List.copyOf(towers);
    }

    /** Same algorithm and order as the original WallService ring: ordered, 8-connected, deterministic per radius. */
    private static List<int[]> ring(int radius) {
        var keys = new HashSet<Long>();
        var out = new ArrayList<int[]>();
        int steps = (int) Math.ceil(2 * Math.PI * radius * 4);
        for (int i = 0; i < steps; i++) {
            double a = 2 * Math.PI * i / steps;
            int dx = (int) Math.round(radius * Math.cos(a)), dz = (int) Math.round(radius * Math.sin(a));
            if (keys.add(key(dx, dz))) out.add(new int[]{dx, dz});
        }
        return out;
    }

    /** Slides a diagonal tower along the ring (+1, -1, +2, -2, …) until it clears every gate and gatehouse. */
    private int towerCenter(int start) {
        int n = ring.size();
        for (int step = 0; step < n; step++) {
            int t = Math.floorMod(start + (step % 2 == 0 ? step / 2 : -(step / 2 + 1)), n);
            if (clear(t)) return t;
        }
        return start;
    }
    private boolean clear(int t) {
        int n = ring.size();
        for (int g : gateCenters()) { int d = Math.floorMod(t - g, n); if (Math.min(d, n - d) < TOWER_GATE_GAP) return false; }
        int[] c = ring.get(t);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long k = key(c[0] + dx, c[1] + dz);
            if (gatehouseCells.contains(k)) return false;
        }
        for (int g : gateCenters()) for (int d = -WallDesign.GATE_HALF_WIDTH; d <= WallDesign.GATE_HALF_WIDTH; d++) {
            int[] o = ring.get(Math.floorMod(g + d, n));
            if (Math.abs(o[0] - c[0]) <= 1 && Math.abs(o[1] - c[1]) <= 1) return false;
        }
        return true;
    }

    public WallDesign design() { return design; }
    /** Ring offsets {dx, dz}; the arrays are shared, callers must not mutate them. */
    public List<int[]> ring() { return ring; }
    public int size() { return ring.size(); }
    /** Whether ring index {@code index} lies inside a main gate opening (3 columns wide). */
    public boolean gate(int index) { return gate[Math.floorMod(index, gate.length)]; }
    public List<Integer> gateCenters() { return gateCenters; }
    public List<Integer> towerCenters() { return towerCenters; }
    /** Whether the offset belongs to this design's wall: ring, built towers and gatehouse columns. */
    public boolean owns(int dx, int dz) {
        long k = key(dx, dz);
        return ringCells.contains(k) || design.towers() && towerCells.contains(k) || design.gatehouses() && gatehouseCells.contains(k);
    }
    public boolean onRing(int dx, int dz) { return ringCells.contains(key(dx, dz)); }
    /** Whether the offset carries a built tower or gatehouse of this design (taller than the plain wall). */
    public boolean raised(int dx, int dz) {
        long k = key(dx, dz);
        return design.towers() && towerCells.contains(k) || design.gatehouses() && gatehouseCells.contains(k);
    }
    /** Whether the offset belongs to any wall this radius and gate count could have, whatever the upgrades. */
    public boolean ownsAnyDesign(int dx, int dz) {
        long k = key(dx, dz);
        return ringCells.contains(k) || towerCells.contains(k) || gatehouseCells.contains(k);
    }
}

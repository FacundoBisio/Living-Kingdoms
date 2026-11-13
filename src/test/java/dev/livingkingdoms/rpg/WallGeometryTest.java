package dev.livingkingdoms.rpg;

import dev.livingkingdoms.rpg.domain.WallDesign;
import dev.livingkingdoms.rpg.domain.WallGeometry;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 0.38 pure wall geometry: ring, gates, towers and footprint ownership. */
class WallGeometryTest {
    private static WallGeometry geo(int radius, int gates) { return WallGeometry.of(radius, new WallDesign(gates, false, false)); }

    @Test void ringMatchesWallServiceAndIsClosed() {
        for (int radius : new int[]{8, 12, 29, 69, 96}) {
            var expected = WallService.ring(radius); var ring = geo(radius, 2).ring();
            assertEquals(expected.size(), ring.size());
            for (int i = 0; i < ring.size(); i++) {
                assertArrayEquals(expected.get(i), ring.get(i), "Same order at r=" + radius + " i=" + i);
                int[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
                assertTrue(Math.abs(a[0] - b[0]) <= 1 && Math.abs(a[1] - b[1]) <= 1, "8-connected and closed at r=" + radius + " i=" + i);
            }
        }
    }

    @Test void gatesAreThreeColumnsAndNeverOverlap() {
        for (int radius = 8; radius <= 96; radius++) for (int gates = 1; gates <= 4; gates++) {
            var g = geo(radius, gates); int open = 0;
            for (int i = 0; i < g.size(); i++) if (g.gate(i)) open++;
            assertEquals(gates * 3, open, "r=" + radius + " gates=" + gates);
            assertEquals(gates, new HashSet<>(g.gateCenters()).size());
        }
    }

    @Test void towersNeverOverlapGates() {
        var failures = new ArrayList<String>();
        for (int radius = 8; radius <= 96; radius++) for (int gates = 1; gates <= 4; gates++) {
            var g = geo(radius, gates); var ring = g.ring(); int n = g.size();
            var blocked = new HashSet<Long>();
            for (int c : g.gateCenters()) for (int d = -3; d <= 3; d++) { int[] o = ring.get(Math.floorMod(c + d, n)); blocked.add(WallGeometry.key(o[0], o[1])); }
            boolean bad = false;
            for (int t : g.towerCenters()) {
                int[] c = ring.get(t);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) bad |= blocked.contains(WallGeometry.key(c[0] + dx, c[1] + dz));
            }
            if (bad) failures.add("r=" + radius + "/g=" + gates);
        }
        assertTrue(failures.isEmpty(), "Towers overlap gates at " + failures);
    }

    @Test void threeGatesFaceCardinalDirections() {
        for (int radius = 8; radius <= 96; radius++) {
            var g = geo(radius, 3);
            for (int c : g.gateCenters()) { int[] o = g.ring().get(c); assertTrue(o[0] == 0 || o[1] == 0, "On an axis at r=" + radius); }
            assertTrue(g.ring().get(g.gateCenters().get(0))[1] > 0, "First gate faces south");
        }
    }

    @Test void ownershipFollowsFootprintAndFlags() {
        var plain = WallGeometry.of(40, new WallDesign(2, false, false));
        var towered = WallGeometry.of(40, new WallDesign(2, true, false));
        for (int[] o : plain.ring()) assertTrue(plain.owns(o[0], o[1]));
        int[] south = plain.ring().get(plain.gateCenters().get(0));
        assertFalse(plain.owns(south[0], south[1] + 2), "Two blocks outside the ring is not wall");
        assertFalse(plain.ownsAnyDesign(south[0], south[1] + 2));
        int[] t = plain.ring().get(plain.towerCenters().get(0));
        int[] corner = null;
        for (int dx = -1; dx <= 1 && corner == null; dx++) for (int dz = -1; dz <= 1; dz++)
            if (!plain.owns(t[0] + dx, t[1] + dz)) { corner = new int[]{t[0] + dx, t[1] + dz}; break; }
        assertNotNull(corner, "A tower footprint reaches off the ring");
        assertFalse(plain.owns(corner[0], corner[1]), "Tower cells are not owned without towers");
        assertTrue(towered.owns(corner[0], corner[1]), "Tower cells are owned with towers");
        assertTrue(plain.ownsAnyDesign(corner[0], corner[1]), "Any design covers towers");
    }

    @Test void cacheReusesAndStaysBounded() {
        assertSame(geo(30, 2), geo(30, 2));
        for (int radius = 8; radius <= 96; radius++) geo(radius, 1);
        assertSame(geo(96, 1), geo(96, 1));
        assertNotSame(geo(30, 2), WallGeometry.of(30, new WallDesign(2, true, false)));
        assertTrue(WallGeometry.cacheSize() <= WallGeometry.CACHE_SIZE);
    }
}

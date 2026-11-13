package dev.livingkingdoms.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BearingTest {
    @Test void cardinalsRelativeToFacing() {
        // Facing south (yaw 0): +Z is ahead, -X (west) is to the right, +X to the left.
        assertEquals("\u2191", Bearing.arrow(0, 10, 0));
        assertEquals("\u2192", Bearing.arrow(-10, 0, 0));
        assertEquals("\u2190", Bearing.arrow(10, 0, 0));
        assertEquals("\u2193", Bearing.arrow(0, -10, 0));
        assertEquals("\u2197", Bearing.arrow(-10, 10, 0));
        assertEquals("\u2196", Bearing.arrow(10, 10, 0));
        // Facing west (yaw 90): a target to the west is straight ahead.
        assertEquals("\u2191", Bearing.arrow(-10, 0, 90));
        assertEquals("\u2193", Bearing.arrow(10, 0, 90));
    }
    @Test void wrapsAroundHalfTurn() {
        assertEquals("\u2193", Bearing.arrow(0, 10, 180));
        assertEquals("\u2193", Bearing.arrow(0, 10, -180));
        assertEquals("\u2191", Bearing.arrow(0, -10, 180), "North target while facing north");
        assertEquals("\u2191", Bearing.arrow(0, -10, -180));
        assertEquals("\u2191", Bearing.arrow(0, 10, 360));
        assertEquals("\u2191", Bearing.arrow(-10, 0, -270));
    }
    @Test void distanceAndHereThreshold() {
        assertEquals(0, Bearing.metres(0, 0));
        assertEquals(5, Bearing.metres(3, 4));
        assertTrue(Bearing.here(0, 0));
        assertTrue(Bearing.here(8, 0));
        assertFalse(Bearing.here(8.5, 0));
        assertEquals("\u2191", Bearing.arrow(0, 0, 0), "Zero distance never throws");
    }
}

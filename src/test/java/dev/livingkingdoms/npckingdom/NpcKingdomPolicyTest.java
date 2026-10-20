package dev.livingkingdoms.npckingdom;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class NpcKingdomPolicyTest {
    @Test void cellsUseFloorDivisionIncludingNegativeCoordinates() {
        assertEquals(new NpcKingdomPolicy.Cell(0, 0), NpcKingdomPolicy.cell(0, 767, 768));
        assertEquals(new NpcKingdomPolicy.Cell(1, -1), NpcKingdomPolicy.cell(768, -1, 768));
        assertEquals(new NpcKingdomPolicy.Cell(-1, -2), NpcKingdomPolicy.cell(-768, -769, 768));
        assertEquals(new NpcKingdomPolicy.Cell(-29297, 29296), NpcKingdomPolicy.cell(-30_000_000, 29_999_999, 1024));
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomPolicy.cell(0, 0, 255));
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomPolicy.cell(0, 0, 4097));
    }

    @Test void cellKeysRoundTripForEverySignCombination() {
        for (int x : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, -29297})
            for (int z : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 29296}) {
                var cell = new NpcKingdomPolicy.Cell(x, z);
                assertEquals(cell, NpcKingdomPolicy.Cell.fromKey(cell.key()));
            }
        assertNotEquals(new NpcKingdomPolicy.Cell(1, 2).key(), new NpcKingdomPolicy.Cell(2, 1).key());
    }

    @Test void rollIsDeterministicBoundedAndSeedDependent() {
        var cell = new NpcKingdomPolicy.Cell(-3, 7);
        double roll = NpcKingdomPolicy.roll(42L, cell);
        assertEquals(roll, NpcKingdomPolicy.roll(42L, cell));
        int selected = 0, differs = 0;
        for (int x = -50; x < 50; x++)
            for (int z = -50; z < 50; z++) {
                var c = new NpcKingdomPolicy.Cell(x, z);
                double value = NpcKingdomPolicy.roll(42L, c);
                assertTrue(value >= 0 && value < 1);
                if (NpcKingdomPolicy.selected(42L, c, 0.35)) selected++;
                if (value != NpcKingdomPolicy.roll(43L, c)) differs++;
                assertFalse(NpcKingdomPolicy.selected(42L, c, 0));
                assertTrue(NpcKingdomPolicy.selected(42L, c, 1));
            }
        // 10,000 cells: the observed share stays close to the configured chance.
        assertTrue(selected > 3200 && selected < 3800, "selected=" + selected);
        assertTrue(differs > 9900);
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomPolicy.selected(1L, cell, 1.5));
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomPolicy.selected(1L, cell, Double.NaN));
    }

    @Test void centerKeepsTerritoryMarginInsideCell() {
        int cellSize = 768, radius = 48, margin = NpcKingdomPolicy.margin(radius);
        assertTrue(margin >= radius);
        for (int x = -20; x < 20; x++)
            for (int z = -20; z < 20; z++) {
                var cell = new NpcKingdomPolicy.Cell(x, z);
                var center = NpcKingdomPolicy.center(99L, cell, cellSize, radius);
                assertEquals(center, NpcKingdomPolicy.center(99L, cell, cellSize, radius));
                assertEquals(cell, NpcKingdomPolicy.cell(center.x(), center.z(), cellSize));
                long minX = (long) x * cellSize, minZ = (long) z * cellSize;
                assertTrue(center.x() - minX >= margin && minX + cellSize - 1 - center.x() >= margin);
                assertTrue(center.z() - minZ >= margin && minZ + cellSize - 1 - center.z() >= margin);
            }
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomPolicy.center(1L, new NpcKingdomPolicy.Cell(0, 0), 256, 128));
    }

    @Test void rulerNamesAreDeterministicVariedAndBounded() {
        var names = new HashSet<String>();
        for (int x = -30; x < 30; x++)
            for (int z = -30; z < 30; z++) {
                var cell = new NpcKingdomPolicy.Cell(x, z);
                String ruler = NpcKingdomPolicy.ruler(7L, cell);
                assertEquals(ruler, NpcKingdomPolicy.ruler(7L, cell));
                assertFalse(ruler.isBlank());
                assertTrue(ruler.length() <= NpcKingdomPolicy.MAX_RULER_LENGTH);
                assertTrue(ruler.chars().allMatch(c -> c == ' ' || Character.isLetter(c)));
                names.add(ruler);
            }
        assertTrue(names.size() > 50, "names=" + names.size());
    }

    @Test void queueIsBoundedDeduplicatedAndFifo() {
        var queue = new NpcKingdomQueue(3);
        assertTrue(queue.offer(5));
        assertFalse(queue.offer(5));
        assertTrue(queue.offer(-1));
        assertTrue(queue.offer(9));
        assertFalse(queue.offer(10));
        assertEquals(3, queue.size());
        assertEquals(5, queue.poll().orElseThrow());
        assertTrue(queue.offer(5));
        assertEquals(-1, queue.poll().orElseThrow());
        assertEquals(9, queue.poll().orElseThrow());
        assertEquals(5, queue.poll().orElseThrow());
        assertTrue(queue.poll().isEmpty());
        assertEquals(NpcKingdomQueue.DEFAULT_CAPACITY, new NpcKingdomQueue().capacity());
    }
}

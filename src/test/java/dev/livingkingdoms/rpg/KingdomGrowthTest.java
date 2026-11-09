package dev.livingkingdoms.rpg;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.rpg.domain.Fortification;
import dev.livingkingdoms.rpg.domain.Technique;
import dev.livingkingdoms.rpg.domain.TownDecor;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.30 domain rules: rank decorations, villager techniques and wall tiers. */
class KingdomGrowthTest {
    @Test void decorTiersUnlockByRankAndApplyOnce() {
        assertTrue(TownDecor.pending(1, 0).isEmpty(), "A fresh hamlet has nothing to decorate yet");
        assertEquals(List.of(TownDecor.LANTERNS), TownDecor.tiersAt(2));
        assertEquals(List.of(TownDecor.LANTERNS, TownDecor.FLOWERS, TownDecor.BANNERS), TownDecor.pending(4, 0));
        int applied = TownDecor.LANTERNS.bit() | TownDecor.FLOWERS.bit();
        assertEquals(List.of(TownDecor.BANNERS), TownDecor.pending(4, applied), "Applied tiers are never repeated");
        assertEquals(List.of(TownDecor.values()), TownDecor.pending(20, 0));
        assertTrue(TownDecor.tiersAt(9).isEmpty());
        var bits = new HashSet<Integer>();
        for (var tier : TownDecor.values()) assertTrue(bits.add(tier.bit()), "Distinct bits");
    }

    @Test void everyWorkerTeachesOneDistinctTechnique() {
        var taught = new HashSet<Technique>();
        for (var type : ProfessionType.values()) Technique.taughtBy(type).ifPresent(t -> assertTrue(taught.add(t), "One technique per profession"));
        assertEquals(Technique.values().length, taught.size(), "Every technique has a teacher");
        assertEquals(Technique.MASTER_SMITH, Technique.taughtBy(ProfessionType.BLACKSMITH).orElseThrow());
        for (var t : Technique.values()) assertEquals(t, Technique.fromId(t.id()));
    }

    @Test void fortificationTiersCostMoreAndUpgradeInOrder() {
        var wall = Fortification.none(40);
        assertEquals(0, wall.tier());
        var palisade = wall.upgrade();
        assertEquals(1, palisade.tier());
        assertFalse(palisade.complete());
        assertEquals(0, palisade.built());
        assertTrue(palisade.progress(5).progress(3).built() == 8);
        var stone = palisade.finished().upgrade();
        assertEquals(2, stone.tier());
        assertThrows(IllegalStateException.class, () -> stone.finished().upgrade().upgrade());
        int[] one = Fortification.cost(1, 40), two = Fortification.cost(2, 40), three = Fortification.cost(3, 40);
        assertTrue(one[0] > 0 && one[1] == 0 && one[2] == 0, "Palisade is timber only");
        assertTrue(two[1] > 0 && three[1] > two[1] && three[2] > two[2], "Stone tiers cost more stone and iron");
        assertTrue(Fortification.cost(2, 80)[1] > Fortification.cost(2, 40)[1], "Bigger rings cost more");
        assertThrows(IllegalArgumentException.class, () -> Fortification.cost(4, 40));
        for (int tier = 1; tier <= Fortification.MAX_TIER; tier++) for (int value : Fortification.cost(tier, 256))
            assertTrue(value <= 256, "Every tier fits a Warehouse-less Kingdom Storage");
        assertThrows(IllegalArgumentException.class, () -> new Fortification(1, 4, 0, false));
    }

    @Test void wallRingIsClosedAndDeterministic() {
        for (int radius : new int[]{8, 21, 45, 96}) {
            var ring = WallService.ring(radius);
            assertEquals(ring.size(), WallService.ring(radius).size());
            var keys = new HashSet<Long>();
            for (int i = 0; i < ring.size(); i++) {
                int[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
                assertTrue(keys.add(((long) a[0] << 32) ^ (a[1] & 0xffffffffL)), "No duplicate columns");
                assertTrue(Math.abs(a[0] - b[0]) <= 1 && Math.abs(a[1] - b[1]) <= 1, "Ring has no gaps at r=" + radius + " i=" + i);
                double distance = Math.hypot(a[0], a[1]);
                assertTrue(Math.abs(distance - radius) < 1, "Columns stay on the circle");
            }
        }
    }
}

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
        assertEquals(List.of(TownDecor.LANTERNS, TownDecor.FLOWERS, TownDecor.BANNERS, TownDecor.VILLAGE_HOMES, TownDecor.OUTSKIRTS), TownDecor.pending(4, 0));
        int applied = TownDecor.LANTERNS.bit() | TownDecor.FLOWERS.bit();
        assertEquals(List.of(TownDecor.BANNERS, TownDecor.VILLAGE_HOMES, TownDecor.OUTSKIRTS), TownDecor.pending(4, applied), "Applied tiers are never repeated");
        assertEquals(List.of(TownDecor.values()), TownDecor.pending(20, 0));
        assertEquals(List.of(TownDecor.CITY_HOMES), TownDecor.tiersAt(9), "Reaching City upgrades every home");
        var bits = new HashSet<Integer>();
        for (var tier : TownDecor.values()) assertTrue(bits.add(tier.bit()), "Distinct bits");
    }

    @Test void stagesFollowRank() {
        assertEquals(dev.livingkingdoms.rpg.domain.KingdomStage.HAMLET, dev.livingkingdoms.rpg.domain.KingdomStage.of(1));
        assertEquals(dev.livingkingdoms.rpg.domain.KingdomStage.VILLAGE, dev.livingkingdoms.rpg.domain.KingdomStage.of(5));
        assertEquals(dev.livingkingdoms.rpg.domain.KingdomStage.TOWN, dev.livingkingdoms.rpg.domain.KingdomStage.of(6));
        assertEquals(dev.livingkingdoms.rpg.domain.KingdomStage.CITY, dev.livingkingdoms.rpg.domain.KingdomStage.of(20));
        assertTrue(dev.livingkingdoms.rpg.domain.KingdomStage.reachedAt(1).isEmpty() && dev.livingkingdoms.rpg.domain.KingdomStage.reachedAt(4).isEmpty());
        assertEquals(dev.livingkingdoms.rpg.domain.KingdomStage.TOWN, dev.livingkingdoms.rpg.domain.KingdomStage.reachedAt(6).orElseThrow());
        assertTrue(dev.livingkingdoms.rpg.domain.KingdomStage.CITY.next().isEmpty());
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

    @Test void wallDesignSpacesGatesAndCycles() {
        var ring = WallService.ring(40);
        for (int gates = 1; gates <= 4; gates++) {
            var design = new dev.livingkingdoms.rpg.domain.WallDesign(gates, false, false);
            int open = 0;
            for (int i = 0; i < ring.size(); i++) if (design.gate(ring, i)) open++;
            assertEquals(gates * 3, open, "Each main gate is three columns wide");
        }
        var d = dev.livingkingdoms.rpg.domain.WallDesign.DEFAULT;
        assertEquals(2, d.gates());
        assertEquals(3, d.nextGates().gates());
        assertEquals(1, d.nextGates().nextGates().nextGates().gates(), "Cycles 2→3→4→1");
        int south = dev.livingkingdoms.rpg.domain.WallDesign.nearest(ring, Math.PI / 2);
        assertTrue(ring.get(south)[1] > 35, "The first gate faces south (+Z)");
        assertThrows(IllegalArgumentException.class, () -> new dev.livingkingdoms.rpg.domain.WallDesign(5, false, false));
    }

    @Test void chapterProgressAcceptsCountsAndClaimsInOrder() {
        var p = dev.livingkingdoms.rpg.domain.ChapterProgress.EMPTY;
        assertEquals(0, p.next(12));
        p = p.accept(0).kill().kill();
        assertEquals(2, p.kills());
        p = p.claim(0);
        assertTrue(p.claimed(0) && p.accepted() == -1 && p.kills() == 0);
        assertEquals(1, p.next(12));
        assertEquals(p, p.kill(), "Kills only count with an accepted mission");
        assertEquals(12, dev.livingkingdoms.rpg.domain.KingdomMission.values().length);
    }
}

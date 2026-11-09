package dev.livingkingdoms.rpg;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.rpg.domain.Bond;
import dev.livingkingdoms.rpg.domain.Expedition;
import dev.livingkingdoms.rpg.domain.PersonalRequest;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FriendshipTest {
    @Test void talkingAndWishesRaiseTheBondOncePerDay() {
        var bond = Bond.empty().talked(10);
        assertEquals(Bond.TALK, bond.value());
        assertSame(bond, bond.talked(10), "Talking twice the same day changes nothing");
        bond = bond.fulfilled(10);
        assertEquals(Bond.TALK + Bond.REQUEST, bond.value());
        var today = bond;
        assertThrows(IllegalStateException.class, () -> today.fulfilled(10));
        assertTrue(bond.canFulfill(11) && bond.canTalk(11));
        assertEquals(Bond.Level.STRANGER, Bond.Level.of(24)); assertEquals(Bond.Level.FRIENDLY, Bond.Level.of(25));
        assertEquals(Bond.Level.DEVOTED, Bond.Level.of(100));
        assertEquals(Bond.MAX, bond.add(500).value()); assertEquals(0, bond.add(-500).value());
    }

    @Test void loyalGiftsAreDailyAndDevotionPaysOnce() {
        var bond = new Bond(80, 0, 0, Long.MIN_VALUE, false);
        assertTrue(bond.loyal() && bond.giftReady(5));
        var gifted = bond.gifted(5);
        assertFalse(gifted.giftReady(5)); assertTrue(gifted.giftReady(6));
        assertFalse(new Bond(60, 0, 0, Long.MIN_VALUE, false).giftReady(5), "Only Loyal citizens give gifts");
        var devoted = new Bond(100, 0, 0, 0, false);
        assertTrue(devoted.devotedRewardReady()); assertFalse(devoted.devotedPaid().devotedRewardReady());
    }

    @Test void wishesAreDeterministicAndVaryAcrossDaysAndCitizens() {
        var citizen = UUID.randomUUID();
        assertEquals(PersonalRequest.of(citizen, 3, ProfessionType.FARMER), PersonalRequest.of(citizen, 3, ProfessionType.FARMER));
        var seen = new HashSet<PersonalRequest>();
        for (int day = 0; day < 40; day++) seen.add(PersonalRequest.of(citizen, day, ProfessionType.FARMER));
        assertTrue(seen.size() > 1, "Wishes change over days");
        for (var type : ProfessionType.values()) assertNotNull(PersonalRequest.of(citizen, 1, type), "Every profession has wishes");
        assertThrows(IllegalArgumentException.class, () -> new PersonalRequest("bread", 1));
    }

    @Test void expeditionsHaveEscalatingDurationsAndOnlyExploreRescues() {
        long previous = 0;
        for (var kind : Expedition.Kind.values()) { assertTrue(kind.duration() > previous); previous = kind.duration(); }
        assertEquals(0, Expedition.Kind.FORAGE.rescuePercent()); assertTrue(Expedition.Kind.EXPLORE.rescuePercent() > 0);
        var trip = Expedition.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Expedition.Kind.MINE, 100);
        assertFalse(trip.due(100 + Expedition.Kind.MINE.duration() - 1)); assertTrue(trip.due(100 + Expedition.Kind.MINE.duration()));
        assertEquals(0, trip.remaining(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new Expedition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Expedition.Kind.MINE, 5, 5));
    }
}

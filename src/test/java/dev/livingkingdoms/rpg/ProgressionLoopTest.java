package dev.livingkingdoms.rpg;

import dev.livingkingdoms.rpg.domain.Contract;
import dev.livingkingdoms.rpg.domain.ContractBoard;
import dev.livingkingdoms.rpg.domain.KingdomRank;
import dev.livingkingdoms.rpg.domain.LootRarity;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionLoopTest {
    @Test void kingdomRankThresholdsAreTriangularAndCapped() {
        assertEquals(0, KingdomRank.threshold(1));
        assertEquals(100, KingdomRank.threshold(2));
        assertEquals(300, KingdomRank.threshold(3));
        assertEquals(1, KingdomRank.level(99)); assertEquals(2, KingdomRank.level(100)); assertEquals(3, KingdomRank.level(300));
        assertEquals(KingdomRank.MAX_LEVEL, KingdomRank.level(KingdomRank.MAX_XP));
        assertEquals(50, KingdomRank.progress(150)); assertEquals(200, KingdomRank.span(150));
        for (int level = 2; level <= KingdomRank.MAX_LEVEL; level++) assertTrue(KingdomRank.threshold(level) > KingdomRank.threshold(level - 1));
        assertEquals(LootRarity.LEGENDARY, KingdomRank.levelUpChest(20));
        assertEquals(LootRarity.EPIC, KingdomRank.levelUpChest(5));
        assertEquals(LootRarity.RARE, KingdomRank.levelUpChest(2));
        assertTrue(KingdomRank.unlocks(2).contains("weekly") && KingdomRank.weeklyUnlocked(2) && !KingdomRank.weeklyUnlocked(1));
        assertEquals(4, KingdomRank.dailyContracts(4)); assertEquals(3, KingdomRank.dailyContracts(1));
    }

    @Test void luckShiftsRarityUpwardAndMinimumIsRespected() {
        var low = counts(0); var high = counts(30);
        assertTrue(high.get(LootRarity.COMMON) < low.get(LootRarity.COMMON));
        assertTrue(high.get(LootRarity.EPIC) + high.get(LootRarity.LEGENDARY) > low.get(LootRarity.EPIC) + low.get(LootRarity.LEGENDARY));
        var random = new Random(4);
        for (int i = 0; i < 500; i++) assertTrue(LootRarity.roll(random, 0, LootRarity.RARE).ordinal() >= LootRarity.RARE.ordinal());
        for (var rarity : LootRarity.values()) assertEquals(rarity, LootRarity.fromId(rarity.id()));
    }

    private static EnumMap<LootRarity, Integer> counts(int luck) {
        var random = new Random(11); var map = new EnumMap<LootRarity, Integer>(LootRarity.class);
        for (var r : LootRarity.values()) map.put(r, 0);
        for (int i = 0; i < 20_000; i++) map.merge(LootRarity.roll(random, luck, LootRarity.COMMON), 1, Integer::sum);
        return map;
    }

    @Test void boardsAreDeterministicPerDayAndRegenerateOnlyOnChange() {
        var a = ContractBoard.empty().refresh(100, 14, 42, 3, true, true);
        var b = ContractBoard.empty().refresh(100, 14, 42, 3, true, true);
        assertEquals(a, b, "Same player and day give the same contracts");
        assertEquals(3, a.daily().size()); assertNotNull(a.weekly());
        assertEquals(3, a.daily().stream().map(Contract::kind).distinct().count(), "Daily kinds do not repeat");
        assertSame(a, a.refresh(100, 14, 42, 3, true, true), "No change inside the same day/week");
        var next = a.refresh(101, 14, 42, 3, true, true);
        assertNotEquals(a.daily(), next.daily()); assertEquals(a.weekly(), next.weekly(), "Weekly survives the daily reset");
        assertNull(ContractBoard.empty().refresh(100, 14, 42, 3, false, true).weekly(), "Weekly locked below rank 2");
        for (int week = 0; week < 50; week++)
            assertNotEquals(Contract.Kind.TRIAL, ContractBoard.empty().refresh(1, week, 7, 3, true, false).weekly().kind(), "No trial weekly before trials unlock");
    }

    @Test void trackedProgressCapsAndClaimsAreOneTime() {
        var board = new ContractBoard(5, java.util.List.of(Contract.of(Contract.Kind.SLAY, "", 3), Contract.of(Contract.Kind.DELIVER, "logs", 10)),
                1, Contract.of(Contract.Kind.QUEST, "", 2), 0, Long.MIN_VALUE, false);
        board = board.progress(Contract.Kind.SLAY, 10);
        assertEquals(3, board.daily().getFirst().progress()); assertTrue(board.daily().getFirst().complete());
        assertTrue(board.daily().get(1).complete(), "Deliveries are verified against the inventory at claim time");
        assertFalse(board.weekly().complete());
        var claimed = board.claimDaily(0);
        assertTrue(claimed.daily().getFirst().claimed());
        assertThrows(IllegalStateException.class, () -> claimed.claimDaily(0));
        assertEquals(claimed, claimed.progress(Contract.Kind.SLAY, 5), "Claimed contracts stop counting");
        assertThrows(IllegalStateException.class, claimed::claimDailyBonus, "Bonus needs every daily claimed");
        assertThrows(IllegalArgumentException.class, () -> Contract.of(Contract.Kind.DELIVER, "diamond", 1));
    }

    @Test void streakGrowsDailyAndDecaysGentlyWhenMissed() {
        var board = ContractBoard.empty();
        for (long day = 10; day < 13; day++) board = completeDay(board, day);
        assertEquals(3, board.streak());
        assertEquals(1.3, board.multiplier(), 1e-9);
        // Skip two days: decays by two, not to zero.
        board = board.refresh(15, 0, 9, 3, false, false);
        assertEquals(1, board.streak());
        board = completeDay(board, 15);
        assertEquals(2, board.streak());
        assertEquals(2.0, ContractBoard.multiplier(50), 1e-9, "Multiplier caps at x2");
        assertEquals(0, ContractBoard.decayedStreak(3, 1, 100));
    }

    private static ContractBoard completeDay(ContractBoard board, long day) {
        board = board.refresh(day, 0, 9, 3, false, false);
        for (int i = 0; i < board.daily().size(); i++) board = board.progress(board.daily().get(i).kind(), 999).claimDaily(i);
        return board.claimDailyBonus();
    }
}

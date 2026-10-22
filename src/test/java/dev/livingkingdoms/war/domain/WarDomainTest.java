package dev.livingkingdoms.war.domain;

import dev.livingkingdoms.encounter.domain.PartyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WarDomainTest {
    @Test void stanceIsSeededStableAndHonoursTheChanceExtremes() {
        UUID kingdom = UUID.fromString("7f1c2a54-3c3b-4f2e-9a0e-1b2c3d4e5f60");
        assertEquals(WarPolicy.stance(42, kingdom, 0.4), WarPolicy.stance(42, kingdom, 0.4));
        assertEquals(KingdomStance.INDEPENDENT, WarPolicy.stance(42, kingdom, 0.0));
        assertEquals(KingdomStance.HOSTILE, WarPolicy.stance(42, kingdom, 1.0));
        int hostile = 0;
        for (int i = 0; i < 2000; i++) if (WarPolicy.stance(7, new UUID(i * 31L, i * 17L + 3), 0.4) == KingdomStance.HOSTILE) hostile++;
        assertTrue(hostile > 650 && hostile < 950, "Roughly 40% hostile: " + hostile);
        assertThrows(IllegalArgumentException.class, () -> WarPolicy.stance(1, kingdom, 1.5));
    }

    @Test void warbandsGrowWithStrengthButStayInsideTheFormation() {
        assertEquals(4, WarPolicy.warbandSize(4, 1));
        assertEquals(5, WarPolicy.warbandSize(4, 3));
        assertEquals(8, WarPolicy.warbandSize(4, 10));
        assertEquals(8, WarPolicy.warbandSize(8, 10));
        assertThrows(IllegalArgumentException.class, () -> WarPolicy.warbandSize(4, 0));
        assertThrows(IllegalArgumentException.class, () -> WarPolicy.warbandSize(9, 1));
    }

    @Test void strongerKingdomsRaidMoreOftenDownToHalfTheCooldown() {
        assertEquals(24000, WarPolicy.raidCooldown(24000, 1));
        assertTrue(WarPolicy.raidCooldown(24000, 5) < 24000);
        assertEquals(12000, WarPolicy.raidCooldown(24000, 10));
        for (int strength = 1; strength < 10; strength++)
            assertTrue(WarPolicy.raidCooldown(24000, strength + 1) <= WarPolicy.raidCooldown(24000, strength));
        assertEquals(1, WarPolicy.raidCooldown(1, 10));
    }

    @Test void approachAnglesStartAtTheBearingAndCoverEveryOctant() {
        double[] angles = WarPolicy.approachAngles(0.3);
        assertEquals(8, angles.length);
        assertEquals(0.3, angles[0], 1e-9);
        assertEquals(0.3 + Math.PI, angles[7], 1e-9);
        java.util.Set<Integer> octants = new java.util.HashSet<>();
        for (double angle : angles) octants.add(Math.floorMod((int) Math.round((angle - 0.3) / (Math.PI / 4)), 8));
        assertEquals(8, octants.size());
        assertTrue(WarPolicy.withinRange(300, 400, 500));
        assertFalse(WarPolicy.withinRange(301, 400, 500));
        assertFalse(WarPolicy.withinRange(Integer.MAX_VALUE, 0, 500));
    }

    @Test void frontStrengthRisesWhenIgnoredAndFallsWhenRepelled() {
        WarFront front = WarFront.initial(KingdomStance.HOSTILE);
        assertTrue(front.raidReady(0));
        front = front.raidLaunched(100, 500);
        assertFalse(front.raidReady(599));
        assertTrue(front.raidReady(600));
        front = front.raidUnanswered().raidUnanswered();
        assertEquals(3, front.strength());
        front = front.raidRepelled();
        assertEquals(2, front.strength());
        assertEquals(1, front.raidsRepelled());
        WarFront weakest = WarFront.initial(KingdomStance.HOSTILE).raidRepelled();
        assertEquals(1, weakest.strength());
        WarFront strongest = WarFront.initial(KingdomStance.HOSTILE);
        for (int i = 0; i < 20; i++) strongest = strongest.raidUnanswered();
        assertEquals(WarFront.MAX_STRENGTH, strongest.strength());
        assertThrows(IllegalStateException.class, () -> WarFront.initial(KingdomStance.INDEPENDENT).raidLaunched(0, 10));
        assertFalse(WarFront.initial(KingdomStance.INDEPENDENT).raidReady(Long.MAX_VALUE));
    }

    @Test void garrisonIsOneAtATimeAndDefeatWeakensTheKingdom() {
        UUID party = UUID.randomUUID();
        WarFront front = WarFront.initial(KingdomStance.HOSTILE).raidUnanswered();
        assertTrue(front.garrisonReady(0));
        front = front.garrisonSpawned(party);
        assertFalse(front.garrisonReady(Long.MAX_VALUE));
        WarFront defeated = front.garrisonCleared(true, 1000, 600);
        assertNull(defeated.garrisonParty());
        assertEquals(1, defeated.strength());
        assertFalse(defeated.garrisonReady(1599));
        assertTrue(defeated.garrisonReady(1600));
        assertEquals(2, front.garrisonCleared(false, 0, 0).strength());
        assertNull(front.withStance(KingdomStance.INDEPENDENT).garrisonParty(), "Peace disbands the garrison record");
        assertThrows(IllegalArgumentException.class, () -> new WarFront(KingdomStance.INDEPENDENT, 1, 0, 0, 0, party, 0, false));
    }

    @Test void bountiesScaleWithLevelSizeAndKindAndRespectCaps() {
        var rules = Bounty.Rules.defaults();
        Bounty patrol = Bounty.forParty(PartyType.PILLAGER_PATROL, 4, 5, 1, rules);
        assertEquals(new Bounty(5, 0, 60), patrol);
        Bounty undead = Bounty.forParty(PartyType.UNDEAD_HORDE, 4, 5, 1, rules);
        assertTrue(undead.emeralds() < patrol.emeralds() && undead.experience() > patrol.experience());
        Bounty warband = Bounty.forParty(PartyType.WARBAND, 6, 10, 4, rules);
        assertEquals(24, warband.emeralds(), "Capped at emeraldCap");
        assertEquals(7, warband.iron());
        assertEquals(270, warband.experience());
        Bounty huge = Bounty.forParty(PartyType.WARBAND, 16, 100, 10, rules);
        assertEquals(rules.emeraldCap(), huge.emeralds());
        assertEquals(16, huge.iron());
        assertEquals(rules.experienceCap(), huge.experience());
        assertTrue(Bounty.forParty(PartyType.BANDIT_PARTY, 2, 1, 1, new Bounty.Rules(0, 0, 0, 0, 0)).empty());
        assertThrows(IllegalArgumentException.class, () -> Bounty.forParty(PartyType.WARBAND, 0, 1, 1, rules));
        assertThrows(IllegalArgumentException.class, () -> new Bounty.Rules(-1, 0, 0, 0, 0));
    }

    @Test void renownRanksAreCumulativeAndRewardsArePaidOnce() {
        assertEquals(RenownRank.RECRUIT, RenownRank.of(0));
        assertEquals(RenownRank.SQUIRE, RenownRank.of(40));
        assertEquals(RenownRank.KNIGHT, RenownRank.of(699));
        assertEquals(RenownRank.WARLORD, RenownRank.of(1_000_000));
        assertTrue(RenownRank.WARLORD.next().isEmpty());
        assertEquals(RenownRank.SERGEANT, RenownRank.SQUIRE.next().orElseThrow());
        for (RenownRank rank : RenownRank.values()) assertEquals(rank, RenownRank.fromId(rank.id()));

        RenownLedger ledger = RenownLedger.empty().add(130);
        assertEquals(List.of(RenownRank.SQUIRE, RenownRank.SERGEANT), ledger.pending());
        ledger = ledger.rewardedThrough(RenownRank.SERGEANT);
        assertTrue(ledger.pending().isEmpty());
        assertSame(ledger, ledger.rewardedThrough(RenownRank.SQUIRE), "A receipt never moves backwards");
        assertEquals(List.of(RenownRank.KNIGHT), ledger.add(200).pending());
        assertEquals(RenownLedger.MAX_POINTS, ledger.add(Integer.MAX_VALUE).points());
        assertThrows(IllegalArgumentException.class, () -> new RenownLedger(10, RenownRank.KNIGHT));
        assertThrows(IllegalArgumentException.class, () -> RenownLedger.empty().add(-1));
    }

    @Test void renownPerDefeatRewardsHarderPartiesAndDoublesWarbands() {
        assertEquals(20, RenownLedger.pointsForDefeat(PartyType.PILLAGER_PATROL, 4, 5));
        assertEquals(24, RenownLedger.pointsForDefeat(PartyType.BANDIT_PARTY, 4, 5.5));
        assertEquals(40, RenownLedger.pointsForDefeat(PartyType.WARBAND, 4, 5));
        assertEquals(RenownLedger.MAX_POINTS_PER_PARTY, RenownLedger.pointsForDefeat(PartyType.WARBAND, 16, 100));
        assertThrows(IllegalArgumentException.class, () -> RenownLedger.pointsForDefeat(PartyType.WARBAND, 4, 0));
    }

    @Test void nearbyWarbandsBecomeWellPaidPriorityContracts() {
        var rules = dev.livingkingdoms.quest.expansion.domain.QuestRules.defaults();
        var level = new dev.livingkingdoms.progression.domain.LevelValue(5);
        var summary = dev.livingkingdoms.progression.domain.LevelSummary.uniform(4, 5);
        var warband = new dev.livingkingdoms.quest.expansion.domain.QuestGenerator.PartyTarget(new UUID(0, 1),
                dev.livingkingdoms.faction.Faction.ENEMY_KINGDOM, PartyType.WARBAND, summary);
        var bandits = new dev.livingkingdoms.quest.expansion.domain.QuestGenerator.PartyTarget(new UUID(0, 2),
                dev.livingkingdoms.faction.Faction.BANDIT, PartyType.BANDIT_PARTY, summary);
        var candidates = dev.livingkingdoms.quest.expansion.domain.QuestGenerator.candidates(level, List.of(warband, bandits), java.util.Map.of(), rules);
        var war = candidates.stream().filter(c -> c.template() == dev.livingkingdoms.quest.expansion.domain.QuestTemplate.WARBAND_REQUEST).findFirst().orElseThrow();
        var outlaws = candidates.stream().filter(c -> c.template() == dev.livingkingdoms.quest.expansion.domain.QuestTemplate.BANDIT_REQUEST).findFirst().orElseThrow();
        assertTrue(war.weight() > outlaws.weight());
        var difficulty = dev.livingkingdoms.quest.expansion.domain.QuestDifficulty.fromLevel(level);
        var warPay = dev.livingkingdoms.quest.expansion.domain.QuestRewardPolicy.forTemplate(dev.livingkingdoms.quest.expansion.domain.QuestTemplate.WARBAND_REQUEST, difficulty, rules);
        var banditPay = dev.livingkingdoms.quest.expansion.domain.QuestRewardPolicy.forTemplate(dev.livingkingdoms.quest.expansion.domain.QuestTemplate.BANDIT_REQUEST, difficulty, rules);
        assertTrue(warPay.emeralds() >= banditPay.emeralds() && warPay.reputation() >= banditPay.reputation());
        assertTrue(warPay.emeralds() > banditPay.emeralds() || warPay.emeralds() == rules.maximumEmeralds());
    }

    @Test void strongKingdomsFieldVeteransAboveTheRegionalLevel() {
        assertEquals(0, WarPolicy.levelBonus(1));
        assertEquals(1, WarPolicy.levelBonus(3));
        assertEquals(4, WarPolicy.levelBonus(10));
        var levels = List.of(new dev.livingkingdoms.progression.domain.LevelValue(3), new dev.livingkingdoms.progression.domain.LevelValue(29));
        assertEquals(List.of(5, 30), dev.livingkingdoms.progression.domain.PartyLevels.withBonus(levels, 2, 30).stream()
                .map(dev.livingkingdoms.progression.domain.LevelValue::value).toList());
        assertEquals(levels, dev.livingkingdoms.progression.domain.PartyLevels.withBonus(levels, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> dev.livingkingdoms.progression.domain.PartyLevels.withBonus(levels, -1, 30));
    }

    @Test void onlyAKingdomAtItsWeakestCapitulatesAndPeaceClearsTheGarrison() {
        UUID garrison = UUID.randomUUID();
        WarFront strong = WarFront.initial(KingdomStance.HOSTILE).withStrength(3).garrisonSpawned(garrison);
        assertFalse(strong.capitulatesOnGarrisonDefeat());
        WarFront weakest = WarFront.initial(KingdomStance.HOSTILE).garrisonSpawned(garrison);
        assertTrue(weakest.capitulatesOnGarrisonDefeat());
        WarFront peace = weakest.withStandardsPlaced().capitulated(500);
        assertEquals(KingdomStance.INDEPENDENT, peace.stance());
        assertNull(peace.garrisonParty());
        assertTrue(peace.standardsPlaced(), "Standards stay as a trophy");
        assertFalse(peace.raidReady(Long.MAX_VALUE) || peace.garrisonReady(Long.MAX_VALUE) || peace.capitulatesOnGarrisonDefeat());
        assertEquals(WarFront.MAX_STRENGTH, weakest.withStrength(99).strength());
        assertEquals(WarFront.MIN_STRENGTH, weakest.withStrength(-5).strength());
    }

    @Test void stanceIdsRoundTrip() {
        for (KingdomStance stance : KingdomStance.values()) assertEquals(stance, KingdomStance.fromId(stance.id()));
        assertThrows(IllegalArgumentException.class, () -> KingdomStance.fromId("peaceful"));
    }
}

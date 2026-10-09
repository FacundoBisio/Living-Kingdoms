package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.defense.DefenseQuestService;
import dev.livingkingdoms.defense.domain.SettlementThreatEvent;
import dev.livingkingdoms.defense.domain.ThreatOutcome;
import dev.livingkingdoms.defense.domain.ThreatState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelSummary;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalDefenseQuestTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID observer = UUID.randomUUID();
    private final UUID settlement = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    private SettlementThreatEvent event(Faction faction, boolean eligible) {
        return new SettlementThreatEvent(UUID.randomUUID(), settlement, "minecraft:overworld", faction, party,
                20, 8, 5, 5, ThreatState.ACTIVE, ThreatOutcome.NONE, 100, 100, -1, 2100,
                !eligible, eligible, Set.of());
    }

    private QuestInstance offer(SettlementThreatEvent event, UUID owner) {
        PartyType type = event.faction() == Faction.UNDEAD ? PartyType.UNDEAD_HORDE : PartyType.PILLAGER_PATROL;
        return DefenseQuestService.offer(event, type, owner, QuestRules.defaults());
    }

    private QuestInstance ordinary(QuestTemplate template) {
        return QuestGenerator.generate(settlement, new LevelValue(8), List.of(), Map.of(),
                QuestRules.defaults(), new Random(11), 100).stream()
                .filter(quest -> quest.template() == template).findFirst().orElseThrow();
    }

    private static QuestSavedData reload(QuestSavedData data) {
        return QuestSavedData.load(data.save(new CompoundTag(), null), null);
    }

    @Test
    void contextualOfferHasStablePlayerIdentityExactTargetAndSharedDifficultyPolicy() {
        var event = event(Faction.PILLAGER, true);
        var first = offer(event, player);
        assertEquals(first, offer(event, player));
        assertNotEquals(first.id(), offer(event, second).id());
        assertNotEquals(first.id(), offer(event(Faction.PILLAGER, true), player).id());
        assertEquals(QuestTemplate.LOCAL_DEFENSE, first.template());
        assertEquals(new QuestSource(settlement, null, QuestSourceRole.BOARD), first.source());
        assertEquals(new QuestObjective.Party(party, Faction.PILLAGER, PartyType.PILLAGER_PATROL), first.objective());
        assertEquals(100, first.createdAt());
        assertEquals(2100, first.expiresAt());
        assertEquals(QuestRewardPolicy.forTemplate(QuestTemplate.PILLAGER_REQUEST,
                QuestDifficulty.NORMAL, QuestRules.defaults()), first.rewards());
        var data = new QuestSavedData();
        assertTrue(data.offer(player, first));
        assertFalse(data.offer(player, offer(event, player)));
        assertEquals(List.of(first), data.quests(player, settlement));
    }

    @Test
    void bothHostilePartyTypesUseTheSameDefenseTemplateWithoutRelaxingOtherTemplates() {
        var undead = offer(event(Faction.UNDEAD, true), player);
        assertEquals(PartyType.UNDEAD_HORDE, ((QuestObjective.Party) undead.objective()).partyType());
        assertThrows(IllegalArgumentException.class, () -> DefenseQuestService.offer(event(Faction.UNDEAD, true),
                PartyType.PILLAGER_PATROL, player, QuestRules.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new QuestInstance(undead.id(), QuestTemplate.PILLAGER_REQUEST,
                new QuestSource(settlement, null, QuestSourceRole.GUARD_CAPTAIN), undead.objective(),
                undead.recommendedLevel(), undead.difficulty(), undead.rewards(), QuestState.AVAILABLE, false, 100, 2100));
    }

    @Test
    void guardOnlyVictoryEndsAcceptedAndAvailableQuestsWithoutPlayerRewards() {
        var event = event(Faction.PILLAGER, true);
        var data = new QuestSavedData();
        var accepted = offer(event, player);
        var available = offer(event, observer);
        assertTrue(data.offer(player, accepted));
        assertTrue(data.offer(observer, available));
        assertTrue(data.acceptExpanded(player, accepted.id()));
        data.resolveDefenseParty(party, settlement, Set.of());
        assertEquals(QuestState.EXPIRED, data.quest(player, accepted.id()).orElseThrow().state());
        assertEquals(QuestState.EXPIRED, data.quest(observer, available.id()).orElseThrow().state());
        assertFalse(data.completeExpanded(player, accepted.id()));
        assertFalse(data.hasDefenseQuest(party));
        assertEquals(0, data.reputation(player, settlement));
        assertEquals(0, data.reputation(observer, settlement));
    }

    @Test
    void multiplayerVictoryRequiresAcceptanceAndFrozenPersonalCredit() {
        var event = event(Faction.UNDEAD, true);
        var data = new QuestSavedData();
        var first = offer(event, player);
        var secondQuest = offer(event, second);
        var unaccepted = offer(event, observer);
        assertTrue(data.offer(player, first));
        assertTrue(data.offer(second, secondQuest));
        assertTrue(data.offer(observer, unaccepted));
        assertTrue(data.acceptExpanded(player, first.id()));
        assertTrue(data.acceptExpanded(second, secondQuest.id()));
        data = reload(data);
        var won = event.withProgress(0).resolved(200, Set.of(player, observer));
        data.resolveDefenseParty(party, settlement, won.participants());
        var ready = data.quest(player, first.id()).orElseThrow();
        assertTrue(DefenseQuestService.canClaim(ready, won, player));
        assertFalse(DefenseQuestService.canClaim(ready, won, second));
        assertEquals(QuestState.EXPIRED, data.quest(second, secondQuest.id()).orElseThrow().state());
        assertEquals(QuestState.EXPIRED, data.quest(observer, unaccepted.id()).orElseThrow().state());
        assertTrue(data.hasDefenseQuest(party));
        assertTrue(data.completeExpanded(player, first.id()));
        assertEquals(first.rewards().reputation(), data.reputation(player, settlement));
        assertEquals(0, data.reputation(second, settlement));
        assertEquals(0, data.reputation(observer, settlement));
        assertFalse(data.hasDefenseQuest(party));
    }

    @Test
    void exactPartyAndSettlementScopeKeepUnrelatedQuestsUntouched() {
        var event = event(Faction.PILLAGER, true);
        var data = new QuestSavedData();
        var defense = offer(event, player);
        assertTrue(data.offer(player, defense));
        assertTrue(data.acceptExpanded(player, defense.id()));
        data.resolveParty(party, Set.of(player));
        data.resolveDefenseParty(UUID.randomUUID(), settlement, Set.of(player));
        data.resolveDefenseParty(party, UUID.randomUUID(), Set.of(player));
        assertFalse(data.quest(player, defense.id()).orElseThrow().objectiveSatisfied());
        data.resolveDefenseParty(party, settlement, Set.of(player));
        assertTrue(data.quest(player, defense.id()).orElseThrow().objectiveSatisfied());
        assertEquals(0, data.reputation(player, settlement));
    }

    @Test
    void acceptedRewardCannotReplayAcrossResolutionReloadOrBoardRefresh() {
        var event = event(Faction.PILLAGER, true);
        var data = new QuestSavedData();
        var quest = offer(event, player);
        assertTrue(data.offer(player, quest));
        assertTrue(data.acceptExpanded(player, quest.id()));
        data.resolveDefenseParty(party, settlement, Set.of(player));
        assertTrue(data.completeExpanded(player, quest.id()));
        data = reload(data);
        data.resolveDefenseParty(party, settlement, Set.of(player));
        assertFalse(data.completeExpanded(player, quest.id()));
        assertFalse(data.offer(player, quest));
        assertTrue(data.rotateBoard(player, settlement, 2200, 200, List.of(ordinary(QuestTemplate.FOOD_REQUEST))));
        var won = event.withProgress(0).resolved(200, Set.of(player));
        assertThrows(IllegalArgumentException.class, () -> offer(won, player));
        assertEquals(quest.rewards().reputation(), data.reputation(player, settlement));
        assertEquals(0, data.reputation(player, UUID.randomUUID()));
    }

    @Test
    void contextualOffersAndReadyRewardsSurviveOrdinaryRotationAndExpiration() {
        var event = event(Faction.PILLAGER, true);
        var data = new QuestSavedData();
        var available = offer(event, player);
        assertTrue(data.offer(player, available));
        assertTrue(data.rotateBoard(player, settlement, 100, 200, List.of(ordinary(QuestTemplate.FOOD_REQUEST))));
        data.expireOffers(player, settlement, 10000);
        assertEquals(QuestState.AVAILABLE, data.quest(player, available.id()).orElseThrow().state());
        assertTrue(data.rotateBoard(player, settlement, 10000, 200, List.of(ordinary(QuestTemplate.IRON_REQUEST))));
        assertEquals(available, data.quest(player, available.id()).orElseThrow());
        assertTrue(data.acceptExpanded(player, available.id()));
        data.resolveDefenseParty(party, settlement, Set.of(player));
        var ready = data.quest(player, available.id()).orElseThrow();
        assertTrue(data.rotateBoard(player, settlement, 10200, 200, List.of(ordinary(QuestTemplate.BUILDING_REQUEST))));
        assertEquals(ready, data.quest(player, available.id()).orElseThrow());
        assertFalse(data.rotateBoard(second, settlement, 100, 200, List.of(offer(event, second))));
    }

    @Test
    void failureClosesAvailableActiveAndUnexpectedReadyDefensesAfterReload() {
        var event = event(Faction.PILLAGER, true);
        var data = new QuestSavedData();
        var first = offer(event, player);
        var unaccepted = offer(event, second);
        assertTrue(data.offer(player, first));
        assertTrue(data.offer(second, unaccepted));
        assertTrue(data.acceptExpanded(player, first.id()));
        // Claim still validates event victory if some unrelated internal caller marks an objective early.
        assertTrue(data.markObjective(player, first.id()));
        assertFalse(DefenseQuestService.canClaim(data.quest(player, first.id()).orElseThrow(), event, player));
        data = reload(data);
        data.failDefenseParty(party, settlement);
        assertEquals(QuestState.FAILED, data.quest(player, first.id()).orElseThrow().state());
        assertEquals(QuestState.FAILED, data.quest(second, unaccepted.id()).orElseThrow().state());
        data.resolveParty(party, Set.of(player));
        data.resolveDefenseParty(party, settlement, Set.of(player));
        assertFalse(data.completeExpanded(player, first.id()));
        assertFalse(data.hasDefenseQuest(party));
        assertEquals(0, data.reputation(player, settlement));
    }

    @Test
    void defaultDebugDefenseHasZeroRewardsAndCannotQualifyForPersonalClaim() {
        var event = event(Faction.UNDEAD, false);
        var quest = offer(event, player);
        assertEquals(new QuestRewards(0, 0), quest.rewards());
        var ready = quest.withState(QuestState.ACTIVE).ready();
        var won = event.withProgress(0).resolved(200, Set.of());
        assertFalse(DefenseQuestService.canClaim(ready, won, player));
        var data = new QuestSavedData();
        assertTrue(data.offer(player, quest));
        assertTrue(data.acceptExpanded(player, quest.id()));
        data.resolveDefenseParty(party, settlement, won.participants());
        assertEquals(QuestState.EXPIRED, data.quest(player, quest.id()).orElseThrow().state());
    }

    @Test
    void claimRejectsDifferentEventIdentityEvenForSameParticipantAndParty() {
        var event = event(Faction.PILLAGER, true);
        var ready = offer(event, player).withState(QuestState.ACTIVE).ready();
        var otherWin = event(Faction.PILLAGER, true).withProgress(0).resolved(200, Set.of(player));
        assertFalse(DefenseQuestService.canClaim(ready, otherWin, player));
        assertTrue(DefenseQuestService.canClaim(ready, event.withProgress(0).resolved(200, Set.of(player)), player));
        assertFalse(DefenseQuestService.canClaim(ready.withState(QuestState.COMPLETED),
                event.withProgress(0).resolved(200, Set.of(player)), player));
    }

    @Test
    void compressedReloadRebuildsAvailableAndReadyDefensePartyReferences() throws IOException {
        var event = event(Faction.PILLAGER, true);
        var data = new QuestSavedData();
        var quest = offer(event, player);
        assertTrue(data.offer(player, quest));
        assertTrue(data.acceptExpanded(player, quest.id()));
        data.resolveDefenseParty(party, settlement, Set.of(player));
        var root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), null));
        var file = directory.resolve("defense-quests.dat");
        NbtIo.writeCompressed(root, file);
        var reopened = QuestSavedData.load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), null);
        assertTrue(reopened.hasDefenseQuest(party));
        assertTrue(DefenseQuestService.canClaim(reopened.quest(player, quest.id()).orElseThrow(),
                event.withProgress(0).resolved(200, Set.of(player)), player));
        assertTrue(reopened.completeExpanded(player, quest.id()));
        assertFalse(reload(reopened).completeExpanded(player, quest.id()));
    }

    @Test
    void ordinaryGeneratorNeverCreatesContextualDefenseOrChangesTheMainChain() {
        var targets = List.of(new QuestGenerator.PartyTarget(party, Faction.PILLAGER,
                        PartyType.PILLAGER_PATROL, LevelSummary.uniform(5, 8)),
                new QuestGenerator.PartyTarget(UUID.randomUUID(), Faction.UNDEAD,
                        PartyType.UNDEAD_HORDE, LevelSummary.uniform(5, 8)));
        assertTrue(QuestGenerator.candidates(new LevelValue(8), targets, Map.of(), QuestRules.defaults())
                .stream().noneMatch(candidate -> candidate.template() == QuestTemplate.LOCAL_DEFENSE));
        assertEquals(List.of(QuestTemplate.FIRST_MEETING, QuestTemplate.MAIN_IRON,
                QuestTemplate.MAIN_PATROL, QuestTemplate.MAIN_RETURN), QuestCatalog.mainChain());
    }
}

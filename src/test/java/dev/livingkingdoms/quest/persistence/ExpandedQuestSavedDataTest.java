package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.domain.QuestTerms;
import dev.livingkingdoms.quest.expansion.domain.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ExpandedQuestSavedDataTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID settlement = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    private QuestInstance offered(QuestTemplate template) {
        return offered(template, settlement, 0, template.category() == QuestCategory.MAIN ? -1 : 2000, new QuestRewards(5, 3));
    }

    private QuestInstance offered(QuestTemplate template, UUID origin, long created, long expires, QuestRewards rewards) {
        QuestObjective objective = switch (template) {
            case FIRST_MEETING -> new QuestObjective.Meet(QuestSourceRole.MAYOR);
            case MAIN_RETURN -> new QuestObjective.Return();
            case MAIN_PATROL, PILLAGER_REQUEST -> new QuestObjective.Party(target, Faction.PILLAGER, PartyType.PILLAGER_PATROL);
            case UNDEAD_REQUEST -> new QuestObjective.Party(target, Faction.UNDEAD, PartyType.UNDEAD_HORDE);
            case FOOD_REQUEST -> new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.WHEAT, 32)));
            case BUILDING_REQUEST -> new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.LOGS, 32),
                    new ResourceRequirement(ResourceKind.STONE, 32)));
            case MAIN_IRON, IRON_REQUEST -> new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT, 16)));
        };
        LevelValue level = new LevelValue(8);
        return new QuestInstance(UUID.randomUUID(), template, new QuestSource(origin, null, template.sourceRole()),
                objective, level, QuestDifficulty.fromLevel(level), rewards, QuestState.AVAILABLE, false, created, expires);
    }

    private void finish(QuestSavedData data, QuestInstance quest) {
        assertTrue(data.offer(player, quest));
        assertTrue(data.acceptExpanded(player, quest.id()));
        assertTrue(data.markObjective(player, quest.id()));
        assertTrue(data.completeExpanded(player, quest.id()));
    }

    private void firstTwo(QuestSavedData data) {
        assertTrue(data.anchorMain(player, settlement));
        finish(data, offered(QuestTemplate.FIRST_MEETING));
        finish(data, offered(QuestTemplate.MAIN_IRON));
    }

    private static QuestSavedData reload(QuestSavedData data) {
        return QuestSavedData.load(data.save(new CompoundTag(), null), null);
    }

    @Test
    void readingFreshExpandedStateDoesNotMutateTheStore() {
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.mainSettlement(player).isEmpty());
        assertTrue(data.quests(player, settlement).isEmpty());
        assertTrue(data.quest(player, UUID.randomUUID()).isEmpty());
        assertEquals(-1, data.boardRefreshAt(player, settlement));
        assertEquals(0, data.boardGeneration(player, settlement));
        assertFalse(data.acceptExpanded(player, UUID.randomUUID()));
        assertFalse(data.isDirty());
        assertEquals(4, data.save(new CompoundTag(), null).getInt("schema_version"));
    }

    @Test
    void mainOrderingRequiresOneGlobalAnchorAndEveryEarlierCompletedStep() {
        QuestSavedData data = new QuestSavedData();
        assertFalse(data.offer(player, offered(QuestTemplate.FIRST_MEETING)));
        assertTrue(data.anchorMain(player, settlement));
        data.setDirty(false);
        assertTrue(data.anchorMain(player, settlement));
        assertFalse(data.anchorMain(player, UUID.randomUUID()));
        assertFalse(data.isDirty());
        assertFalse(data.offer(player, offered(QuestTemplate.MAIN_IRON)));
        assertFalse(data.offer(player, offered(QuestTemplate.MAIN_RETURN)));
        QuestInstance meeting = offered(QuestTemplate.FIRST_MEETING);
        assertTrue(data.offer(player, meeting));
        assertFalse(data.offer(player, offered(QuestTemplate.FIRST_MEETING)));
        assertTrue(data.acceptExpanded(player, meeting.id()));
        assertFalse(data.completeExpanded(player, meeting.id()));
        assertFalse(data.offer(player, offered(QuestTemplate.MAIN_IRON)));
        assertTrue(data.markObjective(player, meeting.id()));
        assertTrue(data.completeExpanded(player, meeting.id()));
        finish(data, offered(QuestTemplate.MAIN_IRON));
        QuestInstance patrol = offered(QuestTemplate.MAIN_PATROL);
        assertTrue(data.offer(player, patrol));
        assertTrue(data.acceptExpanded(player, patrol.id()));
        data.resolveParty(target, Set.of(player));
        assertTrue(data.completeExpanded(player, patrol.id()));
        finish(data, offered(QuestTemplate.MAIN_RETURN));
        QuestSavedData reopened = reload(data);
        assertEquals(settlement, reopened.mainSettlement(player).orElseThrow());
        assertEquals(4, reopened.quests(player, settlement).size());
        assertTrue(reopened.quests(player, settlement).stream().allMatch(quest -> quest.state() == QuestState.COMPLETED));
        assertEquals(12, reopened.reputation(player, settlement));
    }

    @Test
    void failedMainPatrolCanBeReplacedAndOldUuidCanNoLongerResolve() {
        QuestSavedData data = new QuestSavedData();
        firstTwo(data);
        QuestInstance failed = offered(QuestTemplate.MAIN_PATROL);
        assertTrue(data.offer(player, failed));
        assertTrue(data.acceptExpanded(player, failed.id()));
        data.resolveParty(target, Set.of());
        assertEquals(QuestState.FAILED, data.quest(player, failed.id()).orElseThrow().state());
        QuestInstance retry = offered(QuestTemplate.MAIN_PATROL);
        assertTrue(data.offer(player, retry));
        assertTrue(data.quest(player, failed.id()).isEmpty());
        assertFalse(data.completeExpanded(player, failed.id()));
        assertTrue(data.acceptExpanded(player, retry.id()));
        data.resolveParty(target, Set.of(player));
        assertTrue(data.completeExpanded(player, retry.id()));
        assertEquals(3, data.quests(player, settlement).size());
    }

    @Test
    void rotationPersistsCooldownAndGenerationWhileRetainingAcceptedSnapshots() {
        QuestSavedData data = new QuestSavedData();
        QuestInstance active = offered(QuestTemplate.BUILDING_REQUEST);
        QuestInstance available = offered(QuestTemplate.FOOD_REQUEST);
        assertTrue(data.rotateBoard(player, settlement, 100, 1000, List.of(active, available)));
        assertTrue(data.acceptExpanded(player, active.id()));
        QuestSavedData reopened = reload(data);
        assertEquals(100, reopened.boardRefreshAt(player, settlement));
        assertEquals(1, reopened.boardGeneration(player, settlement));
        assertFalse(reopened.rotateBoard(player, settlement, 1099, 1000, List.of(offered(QuestTemplate.IRON_REQUEST))));
        assertFalse(reopened.rotateBoard(player, settlement, 99, 1000, List.of()));
        QuestInstance replacement = offered(QuestTemplate.IRON_REQUEST);
        assertTrue(reopened.rotateBoard(player, settlement, 1100, 1000, List.of(replacement)));
        assertTrue(reopened.quest(player, available.id()).isEmpty());
        assertEquals(active.withState(QuestState.ACTIVE), reopened.quest(player, active.id()).orElseThrow());
        assertEquals(2, reopened.boardGeneration(player, settlement));
        assertEquals(1100, reopened.boardRefreshAt(player, settlement));
        assertEquals(2, reopened.quests(player, settlement).size());
    }

    @Test
    void invalidRotationIsAtomicAndCannotResetTheCooldownOrPruneOffers() {
        QuestSavedData data = new QuestSavedData();
        QuestInstance original = offered(QuestTemplate.FOOD_REQUEST);
        assertTrue(data.rotateBoard(player, settlement, 100, 1000, List.of(original)));
        data.setDirty(false);
        assertFalse(data.rotateBoard(player, settlement, 1100, 1000, List.of(original)));
        QuestInstance otherSettlement = offered(QuestTemplate.IRON_REQUEST, UUID.randomUUID(), 0, 2000, new QuestRewards(5, 3));
        assertFalse(data.rotateBoard(player, settlement, 1100, 1000, List.of(otherSettlement)));
        assertFalse(data.rotateBoard(player, settlement, 1100, 1000, List.of(offered(QuestTemplate.FIRST_MEETING))));
        QuestInstance duplicate = offered(QuestTemplate.BUILDING_REQUEST);
        assertFalse(data.rotateBoard(player, settlement, 1100, 1000, List.of(duplicate, duplicate)));
        assertThrows(IllegalArgumentException.class, () -> data.rotateBoard(player, settlement, 1100, 1000,
                List.of(offered(QuestTemplate.FOOD_REQUEST), offered(QuestTemplate.IRON_REQUEST), offered(QuestTemplate.FOOD_REQUEST),
                        offered(QuestTemplate.IRON_REQUEST), offered(QuestTemplate.BUILDING_REQUEST))));
        assertEquals(List.of(original), data.quests(player, settlement));
        assertEquals(100, data.boardRefreshAt(player, settlement));
        assertEquals(1, data.boardGeneration(player, settlement));
        assertFalse(data.isDirty());
    }

    @Test
    void onlyUnacceptedDynamicOffersExpireAndExpiredStateSurvivesReload() {
        QuestSavedData data = new QuestSavedData();
        data.anchorMain(player, settlement);
        QuestInstance main = offered(QuestTemplate.FIRST_MEETING);
        QuestInstance available = offered(QuestTemplate.FOOD_REQUEST);
        QuestInstance active = offered(QuestTemplate.IRON_REQUEST);
        assertTrue(data.offer(player, main));
        assertTrue(data.offer(player, available));
        assertTrue(data.offer(player, active));
        assertTrue(data.acceptExpanded(player, active.id()));
        data.expireOffers(player, settlement, 1999);
        assertEquals(QuestState.AVAILABLE, data.quest(player, available.id()).orElseThrow().state());
        data.expireOffers(player, settlement, 2000);
        QuestSavedData reopened = reload(data);
        assertEquals(QuestState.EXPIRED, reopened.quest(player, available.id()).orElseThrow().state());
        assertFalse(reopened.acceptExpanded(player, available.id()));
        assertEquals(QuestState.ACTIVE, reopened.quest(player, active.id()).orElseThrow().state());
        assertEquals(QuestState.AVAILABLE, reopened.quest(player, main.id()).orElseThrow().state());
    }

    @Test
    void partyResolutionUsesExactUuidAndValidatedPerPlayerParticipation() {
        QuestSavedData data = new QuestSavedData();
        UUID secondPlayer = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        QuestInstance first = offered(QuestTemplate.PILLAGER_REQUEST);
        QuestInstance second = offered(QuestTemplate.PILLAGER_REQUEST);
        QuestInstance unaccepted = offered(QuestTemplate.PILLAGER_REQUEST);
        assertTrue(data.offer(player, first));
        assertTrue(data.offer(secondPlayer, second));
        assertTrue(data.offer(observer, unaccepted));
        assertTrue(data.acceptExpanded(player, first.id()));
        assertTrue(data.acceptExpanded(secondPlayer, second.id()));
        data.resolveParty(UUID.randomUUID(), Set.of(player, secondPlayer));
        assertFalse(data.quest(player, first.id()).orElseThrow().objectiveSatisfied());
        data.resolveParty(target, Set.of(player, observer));
        assertTrue(data.quest(player, first.id()).orElseThrow().objectiveSatisfied());
        assertEquals(QuestState.FAILED, data.quest(secondPlayer, second.id()).orElseThrow().state());
        assertEquals(QuestState.AVAILABLE, data.quest(observer, unaccepted.id()).orElseThrow().state());
        assertFalse(data.completeExpanded(secondPlayer, first.id()));
        assertTrue(data.completeExpanded(player, first.id()));
        assertEquals(3, data.reputation(player, settlement));
        assertEquals(0, data.reputation(secondPlayer, settlement));
        assertEquals(0, data.reputation(observer, settlement));
    }

    @Test
    void reloadingRebuildsThePartyIndexAndBothFactionsUseTheSameTransition() {
        QuestSavedData data = new QuestSavedData();
        QuestInstance undead = offered(QuestTemplate.UNDEAD_REQUEST);
        assertTrue(data.offer(player, undead));
        assertTrue(data.acceptExpanded(player, undead.id()));
        QuestSavedData reopened = reload(data);
        reopened.resolveParty(target, Set.of(player));
        assertTrue(reopened.quest(player, undead.id()).orElseThrow().objectiveSatisfied());
        assertTrue(reopened.completeExpanded(player, undead.id()));
        reopened.resolveParty(target, Set.of(player));
        assertFalse(reopened.completeExpanded(player, undead.id()));
        assertEquals(3, reload(reopened).reputation(player, settlement));
    }

    @Test
    void rewardIsOncePerQuestAndUsesOnlyItsSourceSettlementIncludingAfterReload() {
        QuestSavedData data = new QuestSavedData();
        QuestInstance quest = offered(QuestTemplate.BUILDING_REQUEST);
        finish(data, quest);
        assertFalse(data.markObjective(player, quest.id()));
        assertFalse(data.completeExpanded(player, quest.id()));
        assertFalse(data.acceptExpanded(player, quest.id()));
        assertFalse(data.offer(player, quest));
        QuestSavedData reopened = reload(data);
        assertFalse(reopened.completeExpanded(player, quest.id()));
        assertFalse(reopened.failExpanded(player, quest.id()));
        assertEquals(3, reopened.reputation(player, settlement));
        assertEquals(0, reopened.reputation(player, UUID.randomUUID()));
    }

    @Test
    void reputationOverflowCannotPartiallyCompleteAReadyQuest() {
        QuestSavedData data = new QuestSavedData();
        data.accept(player, settlement, new QuestTerms(16, 8, 10));
        QuestInstance quest = offered(QuestTemplate.IRON_REQUEST);
        assertTrue(data.offer(player, quest));
        assertTrue(data.acceptExpanded(player, quest.id()));
        assertTrue(data.markObjective(player, quest.id()));
        CompoundTag saved = data.save(new CompoundTag(), null);
        saved.getList("player_settlements", Tag.TAG_COMPOUND).getCompound(0).putInt("reputation", Integer.MAX_VALUE);
        QuestSavedData reopened = QuestSavedData.load(saved, null);
        assertThrows(ArithmeticException.class, () -> reopened.completeExpanded(player, quest.id()));
        assertEquals(QuestState.ACTIVE, reopened.quest(player, quest.id()).orElseThrow().state());
        assertTrue(reopened.quest(player, quest.id()).orElseThrow().objectiveSatisfied());
        assertEquals(Integer.MAX_VALUE, reopened.reputation(player, settlement));
        assertFalse(reopened.isDirty());
    }

    @Test
    void sourceNpcUuidObjectivesAndAllLifecycleStatesRoundTrip() {
        QuestSavedData data = new QuestSavedData();
        firstTwo(data);
        QuestInstance patrol = offered(QuestTemplate.MAIN_PATROL);
        assertTrue(data.offer(player, patrol));
        QuestInstance original = offered(QuestTemplate.BUILDING_REQUEST);
        QuestInstance namedSource = new QuestInstance(original.id(), original.template(),
                new QuestSource(settlement, UUID.randomUUID(), QuestSourceRole.CITIZEN), original.objective(),
                original.recommendedLevel(), original.difficulty(), original.rewards(), original.state(), false, 0, 2000);
        assertTrue(data.offer(player, namedSource));
        assertTrue(data.acceptExpanded(player, namedSource.id()));
        QuestInstance failed = offered(QuestTemplate.UNDEAD_REQUEST);
        assertTrue(data.offer(player, failed));
        assertTrue(data.acceptExpanded(player, failed.id()));
        assertTrue(data.failExpanded(player, failed.id()));
        QuestInstance expired = offered(QuestTemplate.FOOD_REQUEST);
        assertTrue(data.offer(player, expired));
        data.expireOffers(player, settlement, 2000);
        assertEquals(data.quests(player, settlement), reload(data).quests(player, settlement));
        assertFalse(reload(data).isDirty());
    }

    @Test
    void compressedWorldFileKeepsTheMainAnchorActiveObjectivesAndRotation() throws IOException {
        QuestSavedData data = new QuestSavedData();
        firstTwo(data);
        QuestInstance combat = offered(QuestTemplate.PILLAGER_REQUEST);
        assertTrue(data.rotateBoard(player, settlement, 700, 1000, List.of(combat, offered(QuestTemplate.BUILDING_REQUEST))));
        assertTrue(data.acceptExpanded(player, combat.id()));
        Path file = directory.resolve(QuestSavedData.DATA_NAME + ".dat");
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), null));
        NbtIo.writeCompressed(root, file);
        QuestSavedData reopened = QuestSavedData.load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), null);
        assertEquals(settlement, reopened.mainSettlement(player).orElseThrow());
        assertEquals(700, reopened.boardRefreshAt(player, settlement));
        assertEquals(data.quests(player, settlement), reopened.quests(player, settlement));
        reopened.resolveParty(target, Set.of(player));
        assertTrue(reopened.completeExpanded(player, combat.id()));
        assertEquals(9, reopened.reputation(player, settlement));
    }

    @Test
    void oldSchemasOneThroughThreeKeepLegacyQuestMayorAndReputation() {
        QuestSavedData data = new QuestSavedData();
        QuestTerms legacy = new QuestTerms(16, 8, 10);
        UUID mayor = UUID.randomUUID();
        assertTrue(data.accept(player, settlement, legacy));
        assertTrue(data.complete(player, settlement));
        assertTrue(data.associateMayor(settlement, mayor));
        for (int version = 1; version <= 3; version++) {
            CompoundTag old = data.save(new CompoundTag(), null);
            old.putInt("schema_version", version);
            old.remove("expanded_players");
            if (version == 1) old.remove("encounter_rewards");
            QuestSavedData reopened = QuestSavedData.load(old, null);
            assertEquals(QuestState.COMPLETED, reopened.progress(player, settlement).state());
            assertEquals(legacy, reopened.progress(player, settlement).terms());
            assertEquals(10, reopened.reputation(player, settlement));
            assertEquals(mayor, reopened.mayor(settlement).orElseThrow());
            assertTrue(reopened.quests(player, settlement).isEmpty());
            assertTrue(reopened.mainSettlement(player).isEmpty());
            assertEquals(4, reopened.save(new CompoundTag(), null).getInt("schema_version"));
        }
    }

    @Test
    void legacySchemaNumbersCannotSilentlyDiscardNonemptyExpandedRecords() {
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.offer(player, offered(QuestTemplate.FOOD_REQUEST)));
        for (int version = 1; version <= 3; version++) {
            CompoundTag downgraded = data.save(new CompoundTag(), null);
            downgraded.putInt("schema_version", version);
            assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(downgraded, null));
            downgraded.put("expanded_players", new ListTag());
            assertTrue(QuestSavedData.load(downgraded, null).quests(player, settlement).isEmpty());
        }
    }

    @Test
    void malformedNewRecordsAndFutureSchemasAreRejected() {
        QuestSavedData data = new QuestSavedData();
        assertTrue(data.offer(player, offered(QuestTemplate.PILLAGER_REQUEST)));
        CompoundTag valid = data.save(new CompoundTag(), null);
        malformed(valid, tag -> tag.putInt("schema_version", 5));
        malformed(valid, tag -> tag.remove("expanded_players"));
        malformed(valid, tag -> expandedPlayer(tag).remove("player"));
        malformed(valid, tag -> expandedPlayer(tag).remove("boards"));
        malformed(valid, tag -> expandedQuest(tag).remove("id"));
        malformed(valid, tag -> expandedQuest(tag).putString("template", "unknown"));
        malformed(valid, tag -> expandedQuest(tag).getCompound("source").putString("npc", "broken"));
        malformed(valid, tag -> expandedQuest(tag).getCompound("source").putString("role", "MAYOR"));
        malformed(valid, tag -> expandedQuest(tag).putInt("recommended_level", 101));
        malformed(valid, tag -> expandedQuest(tag).putString("difficulty", "VERY_EASY"));
        malformed(valid, tag -> expandedQuest(tag).putByte("objective_satisfied", (byte) 2));
        malformed(valid, tag -> expandedQuest(tag).putString("state", "COMPLETED"));
        malformed(valid, tag -> expandedQuest(tag).putLong("created_at", -1));
        malformed(valid, tag -> expandedQuest(tag).getCompound("objective").putString("faction", "allied_kingdom"));
        malformed(valid, tag -> expandedQuest(tag).getCompound("objective").remove("party"));
        malformed(valid, tag -> expandedPlayer(tag).getList("quests", Tag.TAG_COMPOUND).add(expandedQuest(tag).copy()));
        malformed(valid, tag -> tag.getList("expanded_players", Tag.TAG_COMPOUND).add(expandedPlayer(tag).copy()));
    }

    @Test
    void malformedMainAnchorPrerequisitesAndBoardMetadataAreRejected() {
        QuestSavedData data = new QuestSavedData();
        firstTwo(data);
        assertTrue(data.rotateBoard(player, settlement, 100, 1000, List.of(offered(QuestTemplate.FOOD_REQUEST))));
        CompoundTag valid = data.save(new CompoundTag(), null);
        malformed(valid, tag -> expandedPlayer(tag).remove("main_settlement"));
        malformed(valid, tag -> expandedPlayer(tag).putUUID("main_settlement", UUID.randomUUID()));
        malformed(valid, tag -> expandedPlayer(tag).getList("quests", Tag.TAG_COMPOUND).remove(0));
        malformed(valid, tag -> expandedPlayer(tag).getList("boards", Tag.TAG_COMPOUND).getCompound(0).putLong("generation", 0));
        malformed(valid, tag -> expandedPlayer(tag).getList("boards", Tag.TAG_COMPOUND).getCompound(0).putLong("refreshed_at", -1));
        malformed(valid, tag -> {
            ListTag boards = expandedPlayer(tag).getList("boards", Tag.TAG_COMPOUND);
            boards.add(boards.getCompound(0).copy());
        });
    }

    @Test
    void terminalStatesCannotBeReactivatedOrReceiveDifferentTerms() {
        QuestSavedData data = new QuestSavedData();
        QuestInstance active = offered(QuestTemplate.IRON_REQUEST);
        assertTrue(data.offer(player, active));
        assertTrue(data.acceptExpanded(player, active.id()));
        assertFalse(data.acceptExpanded(player, active.id()));
        assertFalse(data.offer(player, active));
        assertTrue(data.failExpanded(player, active.id()));
        assertFalse(data.failExpanded(player, active.id()));
        assertFalse(data.markObjective(player, active.id()));
        assertFalse(data.completeExpanded(player, active.id()));
        assertFalse(data.acceptExpanded(player, active.id()));
        assertEquals(0, data.reputation(player, settlement));
    }

    private static CompoundTag expandedPlayer(CompoundTag tag) {
        return tag.getList("expanded_players", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static CompoundTag expandedQuest(CompoundTag tag) {
        return expandedPlayer(tag).getList("quests", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static void malformed(CompoundTag valid, Consumer<CompoundTag> mutation) {
        CompoundTag invalid = valid.copy();
        mutation.accept(invalid);
        assertThrows(IllegalArgumentException.class, () -> QuestSavedData.load(invalid, null));
    }
}

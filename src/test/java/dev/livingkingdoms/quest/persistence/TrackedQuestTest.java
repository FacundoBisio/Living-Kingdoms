package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TrackedQuestTest {
    @Test void malformedOrForeignSavedTrackingCannotBecomePlayerState() {
        var data=new QuestSavedData(); var player=UUID.randomUUID(); var q=offer(UUID.randomUUID());
        data.offer(player,q); data.acceptExpanded(player,q.id());
        var tag=data.save(new CompoundTag(),null); tag.getList("expanded_players",10).getCompound(0).putUUID("tracked_quest",UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,() -> QuestSavedData.load(tag,null));
        var inactive=data.save(new CompoundTag(),null); data.markObjective(player,q.id()); data.completeExpanded(player,q.id());
        var completed=data.save(new CompoundTag(),null); completed.getList("expanded_players",10).getCompound(0).putUUID("tracked_quest",q.id());
        assertThrows(IllegalArgumentException.class,() -> QuestSavedData.load(completed,null));
        assertEquals(q.id(),QuestSavedData.load(inactive,null).tracked(player).orElseThrow().id());
    }
    @Test void sharedDefenseTerminationClearsObserversTrackedQuestIncludingExpiration() {
        for(boolean victory:new boolean[]{false,true}) {
            var data=new QuestSavedData(); var player=UUID.randomUUID(); var settlement=UUID.randomUUID(); var party=UUID.randomUUID(); var level=new LevelValue(1);
            var q=new QuestInstance(UUID.randomUUID(),QuestTemplate.LOCAL_DEFENSE,new QuestSource(settlement,null,QuestSourceRole.BOARD),
                    new QuestObjective.Party(party,dev.livingkingdoms.faction.Faction.PILLAGER,dev.livingkingdoms.encounter.domain.PartyType.PILLAGER_PATROL),
                    level,QuestDifficulty.fromLevel(level),new QuestRewards(1,2),QuestState.AVAILABLE,false,0,2000);
            data.offer(player,q); data.acceptExpanded(player,q.id());
            if(victory) data.resolveDefenseParty(party,settlement,java.util.Set.of()); else data.failDefenseParty(party,settlement);
            assertEquals(victory?QuestState.EXPIRED:QuestState.FAILED,data.quest(player,q.id()).orElseThrow().state());
            assertTrue(QuestSavedData.load(data.save(new CompoundTag(),null),null).tracked(player).isEmpty());
        }
    }
    private static QuestInstance offer(UUID settlement) {
        var level=new LevelValue(1);
        return new QuestInstance(UUID.randomUUID(),QuestTemplate.IRON_REQUEST,new QuestSource(settlement,null,QuestTemplate.IRON_REQUEST.sourceRole()),
                new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT,16))),level,
                QuestDifficulty.fromLevel(level),new QuestRewards(1,2),QuestState.AVAILABLE,false,0,2000);
    }
    @Test void latestAcceptanceTracksOneQuestWhileOthersRemainActiveAcrossReload() {
        var data=new QuestSavedData(); var player=UUID.randomUUID(); var a=offer(UUID.randomUUID()); var b=offer(UUID.randomUUID());
        assertTrue(data.offer(player,a)); assertTrue(data.offer(player,b)); assertFalse(data.track(player,a.id()));
        assertTrue(data.acceptExpanded(player,a.id())); assertEquals(a.id(),data.tracked(player).orElseThrow().id());
        assertTrue(data.acceptExpanded(player,b.id())); assertEquals(b.id(),data.tracked(player).orElseThrow().id());
        assertEquals(2,data.quests(player).stream().filter(q -> q.state()==QuestState.ACTIVE).count());
        assertTrue(data.track(player,a.id())); data.setDirty(false); assertTrue(data.track(player,a.id())); assertFalse(data.isDirty());
        var loaded=QuestSavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(a.id(),loaded.tracked(player).orElseThrow().id());
        assertFalse(loaded.untrack(player,b.id())); assertTrue(loaded.untrack(player,a.id()));
        assertTrue(QuestSavedData.load(loaded.save(new CompoundTag(),null),null).tracked(player).isEmpty());
    }
    @Test void personalTrackingCannotAccessAnotherPlayerAndTerminalQuestClearsOnlyItsOwnPointer() {
        var data=new QuestSavedData(); var a=UUID.randomUUID(); var b=UUID.randomUUID(); var qa=offer(UUID.randomUUID()); var qb=offer(UUID.randomUUID());
        data.offer(a,qa); data.offer(b,qb); data.acceptExpanded(a,qa.id()); data.acceptExpanded(b,qb.id());
        assertFalse(data.track(a,qb.id())); assertFalse(data.untrack(a,qb.id()));
        assertTrue(data.markObjective(a,qa.id())); assertEquals(qa.id(),data.tracked(a).orElseThrow().id());
        assertTrue(data.completeExpanded(a,qa.id())); assertTrue(data.tracked(a).isEmpty());
        assertEquals(qb.id(),data.tracked(b).orElseThrow().id()); assertTrue(data.failExpanded(b,qb.id())); assertTrue(data.tracked(b).isEmpty());
        assertFalse(data.track(a,qa.id())); assertFalse(data.track(b,qb.id()));
    }
    @Test void claimingUntrackedQuestKeepsCurrentTrackingAndSchemaFourMigratesWithoutInventingChoice() {
        var data=new QuestSavedData(); var player=UUID.randomUUID(); var a=offer(UUID.randomUUID()); var b=offer(UUID.randomUUID());
        data.offer(player,a); data.offer(player,b); data.acceptExpanded(player,a.id()); data.acceptExpanded(player,b.id());
        data.markObjective(player,a.id()); data.completeExpanded(player,a.id()); assertEquals(b.id(),data.tracked(player).orElseThrow().id());
        var tag=data.save(new CompoundTag(),null); tag.putInt("schema_version",4);
        var players=tag.getList("expanded_players",10);
        for(int i=0;i<players.size();i++) players.getCompound(i).remove("tracked_quest");
        assertTrue(QuestSavedData.load(tag,null).tracked(player).isEmpty());
    }
}

package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.quest.domain.*;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.ui.QuestNoticeQueue;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestNoticeTest {
    private static QuestInstance quest(UUID realm) {
        var template=QuestTemplate.FOOD_REQUEST;var level=new LevelValue(1);
        return new QuestInstance(UUID.randomUUID(),template,new QuestSource(realm,null,template.sourceRole()),
                new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.WHEAT,1))),level,QuestDifficulty.fromLevel(level),new QuestRewards(2,3),QuestState.AVAILABLE,false,0,1000);
    }
    @Test void realTransitionsEmitOnceAndReloadNeverReplaysReadyOrClaimedNotices() {
        var d=new QuestSavedData();var player=UUID.randomUUID();var q=quest(UUID.randomUUID());var events=new ArrayList<QuestNotice.Transition>();d.observeTransitions(events::add);
        assertTrue(d.offer(player,q));assertTrue(d.acceptExpanded(player,q.id()));assertTrue(events.isEmpty());
        assertTrue(d.markObjective(player,q.id()));assertFalse(d.markObjective(player,q.id()));assertEquals(1,events.size());
        assertEquals(QuestNotice.Kind.OBJECTIVE_COMPLETED,events.getFirst().notice().kind());
        var reopened=QuestSavedData.load(d.save(new CompoundTag(),null),null);reopened.observeTransitions(events::add);assertFalse(reopened.markObjective(player,q.id()));assertEquals(1,events.size());
        assertTrue(reopened.completeExpanded(player,q.id()));assertFalse(reopened.completeExpanded(player,q.id()));assertEquals(2,events.size());
        assertEquals(QuestNotice.Kind.REWARD_CLAIMED,events.getLast().notice().kind());assertEquals(3,events.getLast().notice().reputation());
        var again=QuestSavedData.load(reopened.save(new CompoundTag(),null),null);again.observeTransitions(events::add);assertFalse(again.completeExpanded(player,q.id()));assertEquals(2,events.size());
    }
    @Test void multiplayerOwnersNeverReceiveEachOthersQuestTransitions() {
        var d=new QuestSavedData();var a=UUID.randomUUID();var b=UUID.randomUUID();var q=quest(UUID.randomUUID());var events=new ArrayList<QuestNotice.Transition>();d.observeTransitions(events::add);
        d.offer(a,q);d.acceptExpanded(a,q.id());assertFalse(d.markObjective(b,q.id()));assertFalse(d.completeExpanded(b,q.id()));assertTrue(events.isEmpty());
        d.markObjective(a,q.id());assertEquals(a,events.getFirst().player());assertEquals(q.id(),events.getFirst().notice().quest());
    }
    @Test void queueDeduplicatesCapsBurstsAndSupersedesUnshownObjectiveWithClaim() {
        var queue=new QuestNoticeQueue(3);var q=quest(UUID.randomUUID());
        var ready=new QuestNotice(q.id(),QuestNotice.Kind.OBJECTIVE_COMPLETED,q.template(),q.source().settlementId(),2,3);
        assertTrue(queue.offer(ready));assertFalse(queue.offer(ready));
        var claimed=new QuestNotice(q.id(),QuestNotice.Kind.REWARD_CLAIMED,q.template(),q.source().settlementId(),2,3);
        assertTrue(queue.offer(claimed));assertEquals(1,queue.size());assertEquals(claimed,queue.poll().orElseThrow());assertFalse(queue.offer(claimed));
        for(int i=0;i<200;i++) queue.offer(new QuestNotice(UUID.randomUUID(),QuestNotice.Kind.MISSION_COMPLETED,q.template(),q.source().settlementId(),0,0));
        assertEquals(3,queue.size());queue.clear();assertTrue(queue.empty());
    }
    @Test void legacyIronRewardEmitsActualFrozenTermsOnceWithoutExpandedMirror() {
        var d=new QuestSavedData();var player=UUID.randomUUID();var realm=UUID.randomUUID();var events=new ArrayList<QuestNotice.Transition>();d.observeTransitions(events::add);
        d.accept(player,realm,new QuestTerms(10,4,6));assertTrue(d.complete(player,realm));assertFalse(d.complete(player,realm));
        assertEquals(1,events.size());assertEquals(4,events.getFirst().notice().emeralds());assertEquals(6,events.getFirst().notice().reputation());
    }
}

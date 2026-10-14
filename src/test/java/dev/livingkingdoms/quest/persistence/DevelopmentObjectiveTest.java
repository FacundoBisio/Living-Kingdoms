package dev.livingkingdoms.quest.persistence;

import dev.livingkingdoms.quest.domain.*;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DevelopmentObjectiveTest {
    private static QuestInstance offer(QuestTemplate template,UUID realm,long at) {
        QuestObjective objective=switch(template.type()) {
            case MEET_NPC->new QuestObjective.Meet(template.sourceRole());
            case RESOURCE_DELIVERY->new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT,1)));
            case RETURN_TO_SETTLEMENT->new QuestObjective.Return();
            default->throw new IllegalArgumentException();
        };
        return new QuestInstance(UUID.randomUUID(),template,new QuestSource(realm,null,template.sourceRole()),objective,new LevelValue(1),QuestDifficulty.VERY_EASY,new QuestRewards(0,0),QuestState.AVAILABLE,false,at,-1);
    }
    private static QuestInstance active(QuestSavedData data,UUID player,UUID realm,long at) {
        assertTrue(data.anchorMain(player,realm));
        for(var t:List.of(QuestTemplate.FIRST_MEETING,QuestTemplate.MAIN_RESIDENTS,QuestTemplate.MAIN_IRON)) {
            var q=offer(t,realm,0);assertTrue(data.offer(player,q));assertTrue(data.acceptExpanded(player,q.id()));assertTrue(data.markObjective(player,q.id()));assertTrue(data.completeExpanded(player,q.id()));
        }
        var q=offer(QuestTemplate.MAIN_DEVELOPMENT,realm,at);assertTrue(data.offer(player,q));assertTrue(data.acceptExpanded(player,q.id()));return q;
    }
    @Test void localConstructionCreditsOnlyAcceptedRelevantRealmAndEmitsOneNoticePerOwner() {
        var d=new QuestSavedData();var realm=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();var c=UUID.randomUUID();
        var qa=active(d,a,realm,20);var qb=active(d,b,realm,20);var other=active(d,c,UUID.randomUUID(),20);
        var notices=new ArrayList<QuestNotice.Transition>();d.observeTransitions(notices::add);
        assertEquals(0,d.resolveDevelopment(realm,19));assertEquals(2,d.resolveDevelopment(realm,20));assertEquals(0,d.resolveDevelopment(realm,21));
        assertTrue(d.quest(a,qa.id()).orElseThrow().objectiveSatisfied());assertTrue(d.quest(b,qb.id()).orElseThrow().objectiveSatisfied());assertFalse(d.quest(c,other.id()).orElseThrow().objectiveSatisfied());
        assertEquals(Set.of(a,b),new HashSet<>(notices.stream().map(QuestNotice.Transition::player).toList()));assertEquals(2,notices.size());
        assertEquals(QuestNotice.Kind.OBJECTIVE_COMPLETED,notices.getFirst().notice().kind());assertEquals(0,d.reputation(a,realm));
        assertEquals(QuestState.ACTIVE,d.quest(a,qa.id()).orElseThrow().state(),"Completion event readies an objective, never auto-claims its quest");
    }
    @Test void reloadRebuildsActiveSubscriptionsButCannotReplayAlreadyReadyOrFailedQuests() {
        var d=new QuestSavedData();var realm=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();var qa=active(d,a,realm,10);var qb=active(d,b,realm,10);
        assertTrue(d.failExpanded(b,qb.id()));var reloaded=QuestSavedData.load(d.save(new CompoundTag(),null),null);
        assertEquals(1,reloaded.resolveDevelopment(realm,11));assertTrue(reloaded.quest(a,qa.id()).orElseThrow().objectiveSatisfied());
        var again=QuestSavedData.load(reloaded.save(new CompoundTag(),null),null);assertEquals(0,again.resolveDevelopment(realm,100));
        assertEquals(QuestState.FAILED,again.quest(b,qb.id()).orElseThrow().state());assertThrows(IllegalArgumentException.class,()->again.resolveDevelopment(realm,-1));
    }
}

package dev.livingkingdoms.gametest;

import dev.livingkingdoms.quest.domain.*;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestNotificationGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void questPacketsReachOnlyOwnerOnceAndReloadDoesNotReplay(GameTestHelper h) {
        var server=h.getLevel().getServer();var owner=new UiTestPlayer(h.getLevel());var other=new UiTestPlayer(h.getLevel());
        var data=QuestSavedData.get(server);
        data.observeTransitions(event->{dev.livingkingdoms.ui.QuestNotificationService.deliver(owner,event);dev.livingkingdoms.ui.QuestNotificationService.deliver(other,event);});
        try {
            var template=QuestTemplate.FOOD_REQUEST;var level=new LevelValue(1);
            var q=new QuestInstance(UUID.randomUUID(),template,new QuestSource(UUID.randomUUID(),null,template.sourceRole()),
                    new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.WHEAT,1))),level,QuestDifficulty.fromLevel(level),new QuestRewards(2,3),QuestState.AVAILABLE,false,0,1000);
            data.offer(owner.getUUID(),q);data.acceptExpanded(owner.getUUID(),q.id());
            h.assertTrue(data.markObjective(owner.getUUID(),q.id()) && !data.markObjective(owner.getUUID(),q.id()) && owner.questNotices.size()==1 && other.questNotices.isEmpty(),"Native S2C ready packet is personal and emitted only once");
            h.assertTrue(owner.questNotices.getFirst().notice().kind()==QuestNotice.Kind.OBJECTIVE_COMPLETED,"Objective notification identifies pending claim");
            h.assertTrue(data.completeExpanded(owner.getUUID(),q.id()) && !data.completeExpanded(owner.getUUID(),q.id()) && owner.questNotices.size()==2,"Native claim packet uses existing persistent receipt");
            h.assertTrue(owner.questNotices.getLast().notice().kind()==QuestNotice.Kind.REWARD_CLAIMED && owner.questNotices.getLast().notice().emeralds()==2,"Claim notice carries frozen reward");
            var loaded=QuestSavedData.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(!loaded.markObjective(owner.getUUID(),q.id()) && !loaded.completeExpanded(owner.getUUID(),q.id()) && owner.questNotices.size()==2 && other.questNotices.isEmpty(),"Reload does not generate notices or cross-player duplicates");
        } finally {data.observeTransitions(event->dev.livingkingdoms.ui.QuestNotificationService.send(server,event));}
        h.succeed();
    }
}

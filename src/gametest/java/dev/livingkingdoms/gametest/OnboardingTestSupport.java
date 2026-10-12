package dev.livingkingdoms.gametest;

import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Explicit pre-0.16 collaborator fixtures keep isolated inventory/UI/defense regressions independent of new onboarding. */
final class OnboardingTestSupport {
    private OnboardingTestSupport() {}
    static void legacyCollaborator(ServerPlayer player, Settlement settlement) {
        var data = QuestSavedData.get(player.server);
        KingdomOnboardingSavedData.get(player.server).enrollLegacy(player.getUUID(),settlement.id(),settlement.name());
        data.preserveLegacyChapter(player.getUUID(),settlement.id());
        for(var template:List.of(QuestTemplate.FIRST_MEETING,QuestTemplate.MAIN_IRON)) {
            if(data.quests(player.getUUID(),settlement.id()).stream().anyMatch(quest -> quest.template()==template)) continue;
            QuestObjective objective=template==QuestTemplate.FIRST_MEETING ? new QuestObjective.Meet(QuestSourceRole.MAYOR)
                    : new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT,16)));
            var quest=new QuestInstance(UUID.randomUUID(),template,new QuestSource(settlement.id(),null,template.sourceRole()),objective,
                    new LevelValue(1),QuestDifficulty.VERY_EASY,new QuestRewards(0,0),QuestState.AVAILABLE,false,0,-1);
            if(!data.offer(player.getUUID(),quest) || !data.acceptExpanded(player.getUUID(),quest.id())
                    || !data.markObjective(player.getUUID(),quest.id()) || !data.completeExpanded(player.getUUID(),quest.id()))
                throw new IllegalStateException("Legacy collaborator fixture could not restore its original completed chapter");
        }
    }
}

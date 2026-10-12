package dev.livingkingdoms.gametest;

import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.onboarding.domain.OnboardingStage;
import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.UiPayloads;
import dev.livingkingdoms.ui.VillageUiService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OnboardingUiGameTests {
    @GameTest(template="empty", timeoutTicks=400)
    public static void mayorDialogueNamingAndSubsequentConversationsUseTheSameSettlement(GameTestHelper helper) {
        var level=helper.getLevel(); var center=helper.absolutePos(new BlockPos(788480,1,788480));
        var forced = new java.util.HashSet<ChunkPos>();
        for(int x=-6;x<=6;x++) for(int z=-6;z<=6;z++) {
            var at=center.offset(x,0,z); level.getChunkAt(at); level.setBlock(at.below(),Blocks.STONE.defaultBlockState(),18);
            forced.add(new ChunkPos(at));
            for(int y=0;y<4;y++) level.setBlock(at.above(y),Blocks.AIR.defaultBlockState(),18);
        }
        // The remote fixture needs entity-ready chunks; production never requests these test tickets.
        for (var chunk : forced) level.setChunkForced(chunk.x,chunk.z,true);
        var settlement=Settlement.founding(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),24),5);
        var storage=SettlementSavedData.get(level.getServer()); storage.add(settlement);
        helper.startSequence().thenWaitUntil(() -> {
            for (var chunk : forced) helper.assertTrue(level.areEntitiesLoaded(chunk.toLong()), "Mayor fixture chunks are entity-ready");
        }).thenIdle(5).thenExecute(() -> {
            try {
            var mayor=NpcService.ensureMayor(level,settlement).orElseThrow(() -> new IllegalStateException("Mayor fixture has no safe loaded position"));
            var player=new UiTestPlayer(level); player.setPos(mayor.getX()+1,mayor.getY(),mayor.getZ());
            helper.assertTrue(VillageUiService.openDialogue(player,mayor),"A real mayor begins onboarding");
            var first=player.snapshots.getLast(); UUID token=first.getUUID("session");
            helper.assertTrue(first.getString("onboarding_stage").equals("WELCOME") && !first.getBoolean("can_help"),"Initial snapshot cannot grant management permission");
            helper.assertTrue(QuestSavedData.get(level.getServer()).quests(player.getUUID(),settlement.id()).stream()
                    .noneMatch(q -> q.template()==QuestTemplate.FIRST_MEETING && q.state()==dev.livingkingdoms.quest.domain.QuestState.COMPLETED),"Contact alone does not skip narrative");
            helper.assertTrue(!VillageUiService.handleMayor(player,new UiPayloads.MayorRequest(UUID.randomUUID(),"REGISTER","Forged")),"Forged UI capability cannot create a kingdom");
            helper.assertTrue(!VillageUiService.handle(player,new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.CONSTRUCTION)),"Management is gated before formal agreement");
            for(String action:new String[]{"NEXT","NEXT","HELP","ACCEPT"})
                helper.assertTrue(VillageUiService.handleMayor(player,new UiPayloads.MayorRequest(token,action,"")),"Server validates each narrative stage");
            helper.assertTrue(player.snapshots.getLast().getString("onboarding_stage").equals("NAMING"),"Confirmation opens naming view");
            helper.assertTrue(!VillageUiService.handleMayor(player,new UiPayloads.MayorRequest(token,"REGISTER","§cBad")),"Formatting abuse is refused");
            helper.assertTrue(VillageUiService.handleMayor(player,new UiPayloads.MayorRequest(token,"REGISTER","V016 Realm "+settlement.id().toString().substring(0,8))),"A validated name registers the same physical settlement");
            var named=storage.get(settlement.id()).orElseThrow();
            helper.assertTrue(named.territory().equals(settlement.territory()) && named.id().equals(settlement.id()) && !named.name().equals(settlement.name()),"Renaming preserves territory and settlement identity");
            helper.assertTrue(!VillageUiService.handleMayor(player,new UiPayloads.MayorRequest(token,"REGISTER","Second name")),"Repeated submission cannot rename a founded kingdom");
            VillageUiService.openDialogue(player,mayor);
            helper.assertTrue(player.snapshots.getLast().getString("onboarding_stage").equals("JOINED"),"Later interactions open normal conversation");
            var persisted=KingdomOnboardingSavedData.load(KingdomOnboardingSavedData.get(level.getServer()).save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            var savedSettlements=SettlementSavedData.load(storage.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            helper.assertTrue(persisted.stage(player.getUUID(),settlement.id())==OnboardingStage.JOINED
                    && savedSettlements.get(settlement.id()).orElseThrow().name().equals(named.name()),"Name and dialogue progress survive SavedData reload");
            var other=new UiTestPlayer(level); other.setPos(player.position()); VillageUiService.openDialogue(other,mayor);
            UUID otherToken=other.snapshots.getLast().getUUID("session");
            helper.assertTrue(!VillageUiService.handleMayor(other,new UiPayloads.MayorRequest(token,"NEXT","")),"Mayor sessions remain private in multiplayer");
            for(String action:new String[]{"NEXT","NEXT","HELP","ACCEPT","REGISTER"})
                helper.assertTrue(VillageUiService.handleMayor(other,new UiPayloads.MayorRequest(otherToken,action,"Hijacked")),"A second player can join the same kingdom");
            helper.assertTrue(storage.get(settlement.id()).orElseThrow().name().equals(named.name()) && KingdomOnboardingService.canHelp(other,named),"Joining cannot rename another player's kingdom");
            } finally {
                for (var chunk : forced) level.setChunkForced(chunk.x,chunk.z,false);
            }
        }).thenSucceed();
    }
}

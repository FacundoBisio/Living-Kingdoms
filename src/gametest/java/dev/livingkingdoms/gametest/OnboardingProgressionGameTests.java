package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.construction.domain.ConstructionProject;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcInteractions;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.QuestService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fresh onboarding uses real NPC interaction and world blocks; construction completion consumes existing validated receipts. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OnboardingProgressionGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void legacyParticipantsEnrollWithoutResettingTermsNamesOrRewards(GameTestHelper helper) {
        var level=helper.getLevel(); var server=level.getServer(); var center=helper.absolutePos(new BlockPos(151024,1,151024));
        var settlement=new Settlement(UUID.randomUUID(),"Legacy Haven",dev.livingkingdoms.faction.Faction.ALLIED_KINGDOM,1,5,
                new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),24));
        var settlements=SettlementSavedData.get(server); settlements.add(settlement);
        UiTestPlayer player=new UiTestPlayer(level); move(player,center);
        var quests=QuestSavedData.get(server); var terms=new dev.livingkingdoms.quest.domain.QuestTerms(23,7,4);
        helper.assertTrue(quests.accept(player.getUUID(),settlement.id(),terms),"Fixture restores a legitimate pre-update accepted iron delivery");
        var before=quests.progress(player.getUUID(),settlement.id());
        helper.assertTrue(KingdomOnboardingService.canHelp(player,settlement),"Existing active formal quest enrolls automatically");
        var onboarding=dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData.get(server);
        helper.assertTrue(onboarding.registration(settlement.id()).orElseThrow().legacy()
                && onboarding.stage(player.getUUID(),settlement.id())==dev.livingkingdoms.onboarding.domain.OnboardingStage.JOINED,
                "Migration persists shared legacy identity and personal completed dialogue");
        helper.assertTrue(quests.progress(player.getUUID(),settlement.id()).equals(before)
                && settlements.get(settlement.id()).orElseThrow().equals(settlement),"Migration never changes old terms, name, territory or rewards");
        helper.assertTrue(quests.mainSettlement(player.getUUID()).isEmpty(),"Legacy resource-only abstract settlements cannot reserve the global chapter without a mayor");
        UUID laterKingdom=UUID.randomUUID();
        helper.assertTrue(quests.associateMayor(laterKingdom,UUID.randomUUID()) && quests.anchorMain(player.getUUID(),laterKingdom)
                && quests.mainSettlement(player.getUUID()).orElseThrow().equals(laterKingdom),"A later mayor-backed kingdom can still become the player's first chapter anchor");
        UiTestPlayer newcomer=new UiTestPlayer(level); move(newcomer,center);
        helper.assertTrue(!KingdomOnboardingService.canHelp(newcomer,settlement),"Another player's old participation cannot grant formal rights to a newcomer");
        var reloaded=dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData.load(onboarding.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        helper.assertTrue(reloaded.stage(player.getUUID(),settlement.id())==dev.livingkingdoms.onboarding.domain.OnboardingStage.JOINED
                && reloaded.registration(settlement.id()).orElseThrow().name().equals("Legacy Haven"),"Migration receipt survives reload with the old kingdom name");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void residentsConstructionAndTowerHaveRealProgressionRequirements(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(150000, 1, 150000));
        var forced = new HashSet<ChunkPos>();
        for (int x=(center.getX()-24)>>4; x<=(center.getX()+24)>>4; x++) for(int z=(center.getZ()-24)>>4; z<=(center.getZ()+24)>>4; z++) {
            level.getChunk(x,z); level.setChunkForced(x,z,true); forced.add(new ChunkPos(x,z));
        }
        for(int x=-24; x<=24; x++) for(int z=-24; z<=24; z++) {
            level.setBlock(center.offset(x,-1,z), Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=0; y<=10; y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        Settlement settlement = Settlement.founding(UUID.randomUUID(), new Territory(level.dimension().location().toString(),
                center.getX(),center.getY(),center.getZ(),40),5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        BlockPos board = center.offset(6,0,0);
        level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        level.setBlock(board,KingdomBlocks.QUEST_BOARD.get().defaultBlockState(),18);
        helper.startSequence().thenWaitUntil(() -> {
            for(var chunk:forced) helper.assertTrue(level.areEntitiesLoaded(chunk.toLong()),"NPC fixture chunks must finish entity loading");
        }).thenIdle(25).thenExecute(() -> {
            try { progression(helper,settlement,board,center); }
            finally { forced.forEach(chunk -> level.setChunkForced(chunk.x,chunk.z,false)); }
        });
    }

    private static void progression(GameTestHelper helper,Settlement original,BlockPos board,BlockPos center) {
        var level=helper.getLevel(); var server=level.getServer(); var quests=QuestSavedData.get(server);
        UiTestPlayer player=new UiTestPlayer(level); move(player,board);
        QuestService.interact(player,board,true,false);
        helper.assertTrue(quests.progress(player.getUUID(),original.id()).state()==QuestState.AVAILABLE,"Fresh board use cannot bypass the mayor");
        Villager mayor=NpcService.ensureMayor(level,original).orElseThrow(); move(player,mayor.blockPosition());
        helper.assertTrue(KingdomOnboardingService.begin(player,original,mayor),"Actual associated mayor starts the narrative");
        for(String action:List.of("NEXT","NEXT","HELP","ACCEPT"))
            helper.assertTrue(KingdomOnboardingService.advance(player,original,mayor,action,""),"Expected persisted narrative transition "+action);
        String kingdomName="Cedar Realm "+original.id().toString().substring(0,8);
        helper.assertTrue(KingdomOnboardingService.advance(player,original,mayor,"REGISTER",kingdomName),"Naming reuses the existing settlement");
        var settlement=SettlementSavedData.get(server).get(original.id()).orElseThrow();
        helper.assertTrue(settlement.territory().equals(original.territory()) && settlement.name().equals(kingdomName),"Registration preserves physical identity and territory");
        move(player,board);
        QuestService.interact(player,board,true,false);
        helper.assertTrue(quests.progress(player.getUUID(),settlement.id()).state()==QuestState.AVAILABLE,"Iron delivery stays locked until residents are known");
        var residents=quest(quests,player,settlement,QuestTemplate.MAIN_RESIDENTS);
        helper.assertTrue(ExpandedQuestService.act(player,board,residents.id(),"accept",false),"Resident introduction accepts locally");
        Villager outsider=EntityType.VILLAGER.create(level); outsider.moveTo(center.getX()+8.5,center.getY(),center.getZ()+2.5,0,0); outsider.setNoAi(true); level.addFreshEntity(outsider);
        move(player,outsider.blockPosition()); NpcInteractions.onInteract(new PlayerInteractEvent.EntityInteract(player,InteractionHand.MAIN_HAND,outsider));
        helper.assertTrue(!quests.quest(player.getUUID(),residents.id()).orElseThrow().objectiveSatisfied(),"Unrelated vanilla villagers cannot complete a kingdom objective");
        Villager resident=EntityType.VILLAGER.create(level); resident.moveTo(center.getX()+4.5,center.getY(),center.getZ()+2.5,0,0); resident.setNoAi(true);
        NpcIdentity.attach(resident,settlement.id(),NpcRole.RESIDENT); level.addFreshEntity(resident);
        Citizen citizen=new Citizen(UUID.randomUUID(),resident.getUUID(),settlement.id(),"Elena",new LevelValue(1),CitizenRole.UNASSIGNED,null,CitizenState.ACTIVE,now(helper));
        CitizenSavedData.get(server).register(citizen); ProfessionSavedData.get(server).initialize(citizen);
        move(player,resident.blockPosition()); NpcInteractions.onInteract(new PlayerInteractEvent.EntityInteract(player,InteractionHand.MAIN_HAND,resident));
        helper.assertTrue(quests.quest(player.getUUID(),residents.id()).orElseThrow().objectiveSatisfied(),"Talking to the actual registered inhabitant records objective progress");
        move(player,board); helper.assertTrue(ExpandedQuestService.act(player,board,residents.id(),"claim",false),"Resident knowledge closes only at the local board");
        var iron=quest(quests,player,settlement,QuestTemplate.MAIN_IRON);
        helper.assertTrue(ExpandedQuestService.act(player,board,iron.id(),"accept",false),"The existing real shortage unlocks next");
        int amount=quests.progress(player.getUUID(),settlement.id()).terms().requiredIron();
        player.getInventory().items.set(0,new ItemStack(Items.IRON_INGOT,amount));
        helper.assertTrue(ExpandedQuestService.act(player,board,iron.id(),"claim",false),"Existing inventory delivery supplies useful kingdom resources");
        var development=quest(quests,player,settlement,QuestTemplate.MAIN_DEVELOPMENT);
        helper.assertTrue(ExpandedQuestService.act(player,board,development.id(),"accept",false),"Construction stage unlocks after useful supplies");
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        completedProject(helper,settlement,catalog.get(BuildingKind.HOUSE),center.offset(8,-1,-12),now(helper)-1);
        ExpandedQuestService.refreshDevelopment(player,settlement);
        helper.assertTrue(!quests.quest(player.getUUID(),development.id()).orElseThrow().objectiveSatisfied(),"Old completed buildings cannot complete a newly offered construction objective");
        completedProject(helper,settlement,catalog.get(BuildingKind.HOUSE_VARIANT),center.offset(-18,-1,-12),now(helper));
        ExpandedQuestService.refreshDevelopment(player,settlement);
        helper.assertTrue(quests.quest(player.getUUID(),development.id()).orElseThrow().objectiveSatisfied(),"A new authoritative construction completion advances development");
        helper.assertTrue(ExpandedQuestService.act(player,board,development.id(),"claim",false),"Actual development closes once at the local board");
        var towerQuest=quest(quests,player,settlement,QuestTemplate.MAIN_WATCHTOWER);
        helper.assertTrue(ExpandedQuestService.act(player,board,towerQuest.id(),"accept",false),"Defense briefing unlocks after development");
        var module=catalog.get(BuildingKind.WATCHTOWER); BlockPos origin=center.offset(-10,-1,8);
        helper.assertTrue(module.template().placeInWorld(level,origin,origin,SettlementTemplate.settings(),level.random,18),"Test tower uses the actual native asset");
        var size=module.size(); var bounds=new PlotBounds(origin.getX(),origin.getZ(),origin.getX()+size.getX()-1,origin.getZ()+size.getZ()-1);
        var description=new SettlementLayoutMetadata.Building(BuildingKind.WATCHTOWER,module.id(),origin,Rotation.NONE,bounds,origin.offset(module.entrance()));
        ProfessionSavedData.get(server).synchronize(settlement,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(description),List.of(center),List.of()),0,0,0,List.of(citizen));
        move(player,origin.above());
        var hit=new BlockHitResult(Vec3.atCenterOf(origin),Direction.UP,origin,false);
        ExpandedQuestService.onWatchtowerInteract(new PlayerInteractEvent.RightClickBlock(player,InteractionHand.OFF_HAND,origin,hit));
        helper.assertTrue(!quests.quest(player.getUUID(),towerQuest.id()).orElseThrow().objectiveSatisfied(),"Offhand duplicate events cannot award briefing progress");
        ExpandedQuestService.onWatchtowerInteract(new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,origin,hit));
        helper.assertTrue(quests.quest(player.getUUID(),towerQuest.id()).orElseThrow().objectiveSatisfied(),"Real registered watchtower inspection completes defense knowledge");
        move(player,board); helper.assertTrue(ExpandedQuestService.act(player,board,towerQuest.id(),"claim",false),"Defense briefing claims locally");
        helper.assertTrue(!ExpandedQuestService.act(player,board,towerQuest.id(),"claim",false),"Repeated progression claims remain closed");
        var reloaded=QuestSavedData.load(quests.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        helper.assertTrue(reloaded.completedMain(player.getUUID(),settlement.id()).contains(QuestTemplate.MAIN_WATCHTOWER),"Four progression stages survive persistent reload");
        helper.succeed();
    }

    private static void completedProject(GameTestHelper helper,Settlement settlement,BuildingTemplate module,BlockPos origin,long completed) {
        var size=module.size(); var bounds=new PlotBounds(origin.getX(),origin.getZ(),origin.getX()+size.getX()-1,origin.getZ()+size.getZ()-1);
        var building=new SettlementLayout.Building(module,origin,Rotation.NONE,bounds,origin.offset(module.entrance()),List.of());
        var before=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var block:module.blocks()) before.put(building.position(block.pos()),Blocks.AIR.defaultBlockState());
        var plan=new SettlementLayout(settlement.territory(),ArchitectureStyle.PLAINS,List.of(building),Map.of(),before);
        var project=ConstructionProject.planned(settlement.id(),module.kind(),new ConstructionProject.Plot(origin.getX(),origin.getY(),origin.getZ(),ConstructionProject.Orientation.NONE),
                Map.of(ResourceKind.LOGS,1),20,0).waitForResources().supply(Map.of(ResourceKind.LOGS,1)).start(0).complete(completed);
        ConstructionSavedData.get(helper.getLevel().getServer()).reserve(project,plan);
    }
    private static QuestInstance quest(QuestSavedData data,UiTestPlayer player,Settlement settlement,QuestTemplate type) {
        return data.quests(player.getUUID(),settlement.id()).stream().filter(quest -> quest.template()==type).findFirst().orElseThrow();
    }
    private static void move(UiTestPlayer player,BlockPos position) { player.setPos(position.getX()+.5,position.getY(),position.getZ()+.5); }
    private static long now(GameTestHelper helper) { return helper.getLevel().getServer().overworld().getGameTime(); }
}

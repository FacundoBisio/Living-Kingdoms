package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.npc.*;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageUiGameTests {
    @GameTest(template="empty", timeoutTicks=400)
    public static void uiSessionsValidateOwnerReachInventoryAndSingleReward(GameTestHelper helper) {
        var level=helper.getLevel();
        BlockPos board=helper.absolutePos(new BlockPos(112000,1,112000));
        level.getChunkAt(board);
        level.setBlock(board.below(),Blocks.GRASS_BLOCK.defaultBlockState(),18);
        level.setBlock(board,KingdomBlocks.QUEST_BOARD.get().defaultBlockState(),18);
        var settlement=Settlement.founding(UUID.randomUUID(),new Territory(level.dimension().location().toString(),board.getX(),board.getY(),board.getZ(),24),5);
        SettlementSavedData.get(level.getServer()).add(settlement);
        UiTestPlayer owner=new UiTestPlayer(level), other=new UiTestPlayer(level);
        owner.setPos(board.getX()+2.5,board.getY(),board.getZ()+0.5);other.setPos(owner.position());
        helper.assertTrue(VillageUiService.openBoard(owner,board),"A nearby real board opens a payload");
        var snapshot=owner.snapshots.getLast(); UUID token=snapshot.getUUID("session");
        var data=QuestSavedData.get(level.getServer());
        var quest=data.quests(owner.getUUID(),settlement.id()).stream().filter(q->q.objective() instanceof QuestObjective.Resource).findFirst().orElseThrow();
        helper.assertTrue(!VillageUiService.handle(owner,new UiPayloads.Request(UUID.randomUUID(),quest.id(),UiPayloads.Action.ACCEPT)),"Forged sessions are rejected");
        helper.assertTrue(!VillageUiService.handle(other,new UiPayloads.Request(token,quest.id(),UiPayloads.Action.ACCEPT)),"A session cannot be transferred to another player");
        VillageUiService.openBoard(other,board);
        helper.assertTrue(!VillageUiService.handle(other,new UiPayloads.Request(other.snapshots.getLast().getUUID("session"),quest.id(),UiPayloads.Action.ACCEPT)),"A valid session cannot access someone else's quest UUID");
        helper.assertTrue(VillageUiService.handle(owner,new UiPayloads.Request(token,quest.id(),UiPayloads.Action.ACCEPT)),"A valid GUI request accepts");
        helper.assertTrue(!VillageUiService.handle(owner,new UiPayloads.Request(token,quest.id(),UiPayloads.Action.CLAIM)),"Client claim cannot bypass inventory requirements");
        int slot=0;
        for(var term:((QuestObjective.Resource)quest.objective()).requirements()) owner.getInventory().items.set(slot++,new ItemStack(switch(term.resource()) {
            case IRON_INGOT->Items.IRON_INGOT;case WHEAT->Items.WHEAT;case LOGS->Items.OAK_LOG;case STONE->Items.STONE;
        },term.count()));
        helper.assertTrue(VillageUiService.handle(owner,new UiPayloads.Request(token,quest.id(),UiPayloads.Action.CLAIM)),"Satisfied request grants its reward");
        helper.assertTrue(!VillageUiService.handle(owner,new UiPayloads.Request(token,quest.id(),UiPayloads.Action.CLAIM)),"Repeated packet cannot duplicate a reward");
        helper.assertTrue(data.reputation(owner.getUUID(),settlement.id())==quest.rewards().reputation()
                && data.quest(owner.getUUID(),quest.id()).orElseThrow().state()==QuestState.COMPLETED,"Server owns completion and reputation");
        var saved=QuestSavedData.load(data.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        helper.assertTrue(saved.quest(owner.getUUID(),quest.id()).orElseThrow().state()==QuestState.COMPLETED,"GUI completion survives reload");
        owner.setPos(board.getX()+20,board.getY(),board.getZ());
        helper.assertTrue(!VillageUiService.handle(owner,new UiPayloads.Request(token,quest.id(),UiPayloads.Action.REFRESH))
                && owner.snapshots.getLast().getString("screen").equals("closed"),"Leaving reach invalidates the session");
        owner.setPos(board.getX()+2,board.getY(),board.getZ());VillageUiService.openBoard(owner,board);
        UUID latest=owner.snapshots.getLast().getUUID("session");level.setBlock(board,Blocks.AIR.defaultBlockState(),18);
        helper.assertTrue(!VillageUiService.handle(owner,new UiPayloads.Request(latest,quest.id(),UiPayloads.Action.REFRESH)),"Destroyed boards invalidate their sessions");
        helper.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void mayorDefaultsAndResidentsPersistWithoutPopulationMechanics(GameTestHelper helper) {
        var level=helper.getLevel();BlockPos center=helper.absolutePos(new BlockPos(113024,1,113024));
        for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++) {
            var at=center.offset(x,0,z);level.getChunkAt(at);level.setBlock(at.below(),Blocks.STONE_BRICKS.defaultBlockState(),18);
            for(int y=0;y<4;y++)level.setBlock(at.above(y),Blocks.AIR.defaultBlockState(),18);
        }
        var settlement=Settlement.founding(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),24),5);
        var data=SettlementSavedData.get(level.getServer());data.add(settlement);
        var mayor=NpcService.ensureMayor(level,settlement).orElseThrow();
        mayor.getPersistentData().remove("livingkingdoms:mayor_name");
        mayor.setCustomName(Component.literal("Mayor "+settlement.id()));
        MayorPresentation.apply(mayor);
        helper.assertTrue(!mayor.getCustomName().getString().contains(settlement.id().toString())
                && !mayor.getCustomName().getString().contains("Haven"),"Legacy Mayor presentation is replaced without exposing IDs");
        NpcService.spawnInitialResidents(level,settlement,mayor);
        var receipt=mayor.getPersistentData();
        UUID a=receipt.getUUID("livingkingdoms:founding_resident_0"),b=receipt.getUUID("livingkingdoms:founding_resident_1");
        helper.assertTrue(!a.equals(b),"Two distinct real resident entities are created");
        NpcService.spawnInitialResidents(level,settlement,mayor);
        helper.assertTrue(receipt.getUUID("livingkingdoms:founding_resident_0").equals(a)
                && receipt.getUUID("livingkingdoms:founding_resident_1").equals(b),"Founding receipts prevent duplicate residents");
        var reloaded=EntityType.VILLAGER.create(level);reloaded.load(mayor.saveWithoutId(new CompoundTag()));
        MayorPresentation.apply(reloaded);
        helper.assertTrue(reloaded.getUUID().equals(mayor.getUUID())&&NpcIdentity.read(reloaded).equals(NpcIdentity.read(mayor))
                &&reloaded.getPersistentData().getUUID("livingkingdoms:founding_resident_0").equals(a)
                &&reloaded.getCustomName().equals(mayor.getCustomName()),"Identity, presentation and resident receipts survive entity NBT reload");
        helper.assertTrue(reloaded.isNoAi() && reloaded.isPersistenceRequired()
                && reloaded.getVillagerData().getProfession()==net.minecraft.world.entity.npc.VillagerProfession.CLERIC
                && reloaded.getCustomName().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents title
                && title.getKey().equals("npc.livingkingdoms.mayor.name")
                && !reloaded.getCustomName().getString().matches(".*[a-f0-9]{8}-[a-f0-9-]{27,}.*"),
                "Reload retains ceremonial outfit and synced named circlet eligibility without technical IDs");
        helper.assertTrue(data.get(settlement.id()).orElseThrow().population()==5,"Visual residents do not change abstract population capacity");
        helper.succeed();
    }

    @GameTest(template="empty", timeoutTicks=400)
    public static void redesignedModulesHaveInteriorsAndDistinctRoleLandmarks(GameTestHelper helper) {
        var catalog=BuildingCatalog.load(helper.getLevel(),ArchitectureStyle.PLAINS);
        for(var kind:new BuildingKind[]{BuildingKind.HOUSE,BuildingKind.HOUSE_VARIANT,BuildingKind.HOUSE_THIRD}) {
            var module=catalog.get(kind);
            helper.assertTrue(module.blocks().stream().anyMatch(b->b.state().is(net.minecraft.tags.BlockTags.BEDS))
                    &&module.blocks().stream().anyMatch(b->b.state().is(Blocks.BARREL))
                    &&module.blocks().stream().anyMatch(b->b.state().is(Blocks.LANTERN)),"Every house has a bed, storage and lighting");
        }
        helper.assertTrue(catalog.get(BuildingKind.CORE).size().getY()>catalog.get(BuildingKind.HOUSE).size().getY(),"Hall dominates the roofline");
        helper.assertTrue(catalog.get(BuildingKind.WATCHTOWER).blocks().stream().filter(b->b.state().is(Blocks.LADDER)).count()>=7,"Lookout has continuous ladder access");
        helper.assertTrue(catalog.get(BuildingKind.BLACKSMITH).blocks().stream().anyMatch(b->b.state().is(Blocks.ANVIL))
                &&catalog.get(BuildingKind.BARRACKS).blocks().stream().anyMatch(b->b.state().is(Blocks.TARGET)),"Workshop and training yard have distinct landmarks");
        helper.succeed();
    }
}

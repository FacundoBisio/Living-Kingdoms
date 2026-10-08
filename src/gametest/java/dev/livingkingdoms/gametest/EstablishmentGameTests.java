package dev.livingkingdoms.gametest;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.npc.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.*;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementOrigin;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageUiService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EstablishmentGameTests {
    // Remote fixtures need temporary visibility/ticking like a nearby real player. These tickets are test-only and expire.
    private static final TicketType<ChunkPos> SURVEY_FIXTURE = TicketType.create("livingkingdoms_test_survey",
            java.util.Comparator.comparingLong(ChunkPos::toLong),600);
    @GameTest(template="empty",timeoutTicks=600)
    public static void charterIsRegisteredAndCraftableWithAnyBanner(GameTestHelper helper) {
        var level = helper.getLevel();
        helper.assertTrue(BuiltInRegistries.ITEM.getKey(KingdomItems.KINGDOM_CHARTER.get()).equals(
                ResourceLocation.parse("livingkingdoms:kingdom_charter")),"Charter registration is public survival content");
        var recipe = (CraftingRecipe)level.getRecipeManager().byKey(ResourceLocation.parse("livingkingdoms:kingdom_charter")).orElseThrow().value();
        for (var banner : List.of(Items.WHITE_BANNER,Items.RED_BANNER,Items.BLUE_BANNER)) {
            var input = CraftingInput.of(3,3,List.of(new ItemStack(Items.EMERALD),new ItemStack(Items.PAPER),new ItemStack(Items.EMERALD),
                    new ItemStack(Items.IRON_INGOT),new ItemStack(banner),new ItemStack(Items.IRON_INGOT),
                    new ItemStack(Items.STONE_BRICKS),new ItemStack(Items.STONE_BRICKS),new ItemStack(Items.STONE_BRICKS)));
            helper.assertTrue(recipe.matches(input,level) && recipe.assemble(input,level.registryAccess()).is(KingdomItems.KINGDOM_CHARTER),
                    "Datapack recipe accepts banner colors and yields one usable Charter");
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void conversionPromotesVillagerPreservesVillageAndReusesQuests(GameTestHelper helper) {
        var level = helper.getLevel(); var center = fixture(helper,120000);
        var candidates = village(level,center,false,true);
        // A real inventory-bearing vanilla house is outside the minimal plaza and must survive intact.
        var chest = center.offset(12,0,0); level.setBlock(chest,Blocks.CHEST.defaultBlockState(),18);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(chest)).setItem(0,new ItemStack(Items.DIAMOND,3));
        var player = new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } };
        player.getAbilities().mayBuild=true;
        player.setPos(center.getX()+0.5,center.getY(),center.getZ()+2.5);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(KingdomItems.KINGDOM_CHARTER.get(),2));
        helper.runAfterDelay(2,()-> {
            var survey = VillageSurvey.detect(level,center);
            helper.assertTrue(survey.valid() && survey.beds().size()==2 && survey.bells().size()==1,"Villagers + complete HOME POIs + optional bell: "+survey);
            var result = SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            helper.assertTrue(result.successful(),"A valid village converts: "+result.message().getString());
            var settlement = result.settlement();
            helper.assertTrue(settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED
                    && dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(level.getServer()).projects(settlement.id()).isEmpty(),"Converted villages bypass the wilderness chain");
            var advancement=level.getServer().getAdvancements().get(dev.livingkingdoms.advancement.KingdomMilestone.FIRST_KINGDOM.id());
            helper.assertTrue(player.getAdvancements().getOrStartProgress(advancement).isDone()
                    && !dev.livingkingdoms.advancement.KingdomMilestone.awardFirstKingdom(player),"First conversion qualifies for the same one-time advancement");
            helper.assertTrue(settlement.provenance().origin()==SettlementOrigin.CONVERTED
                    && settlement.provenance().founder().orElseThrow().equals(player.getUUID())
                    && settlement.provenance().createdAtEpochMillis()>0 && settlement.provenance().kingdom().isEmpty(),"Founder, origin, time and empty future kingdom are stored");
            helper.assertTrue(player.getMainHandItem().getCount()==1,"Exactly one Charter consumed");
            var mayor = NpcService.ensureMayor(level,settlement).orElseThrow();
            helper.assertTrue(candidates.contains(mayor) && NpcIdentity.read(mayor).orElseThrow().settlementId().equals(settlement.id()),"Existing villager UUID is promoted");
            helper.assertTrue(mayor.isNoAi() && mayor.isInvulnerable() && mayor.hasCustomName()
                    && !mayor.getCustomName().getString().contains(mayor.getUUID().toString()),"Mayor has ceremonial presentation and proper name");
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST)
                    && ((net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(chest)).getItem(0).getCount()==3
                    && level.getBlockState(center.north(8)).is(Blocks.BELL)
                    && level.getBlockState(center.offset(-8,0,3)).is(Blocks.RED_BED),"House, inventory, bell and beds survive conversion");
            assertInteractions(helper,player,settlement,mayor);
            var saved = SettlementSavedData.get(level.getServer());
            var reopened = SettlementSavedData.load(saved.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            var quests = QuestSavedData.get(level.getServer());
            quests.awardEncounterReputationOnce(UUID.randomUUID(),player.getUUID(),settlement.id(),4);
            var reopenedQuests = QuestSavedData.load(quests.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
            helper.assertTrue(reopened.get(settlement.id()).orElseThrow().equals(settlement)
                    && reopenedQuests.mayor(settlement.id()).orElseThrow().equals(mayor.getUUID())
                    && reopenedQuests.reputation(player.getUUID(),settlement.id())==4
                    && !reopenedQuests.quests(player.getUUID(),settlement.id()).isEmpty(),"Settlement, Mayor, quests and regional rewards reload with the same UUID");
            var reloadedMayor = EntityType.VILLAGER.create(level); reloadedMayor.load(mayor.saveWithoutId(new CompoundTag()));
            helper.assertTrue(NpcIdentity.read(reloadedMayor).equals(NpcIdentity.read(mayor)) && reloadedMayor.getUUID().equals(mayor.getUUID()),"Promoted entity identity persists");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void twoPlayersAndBothHandsCannotConvertTwice(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,121024); village(level,center,false,false);
        var first=player(level,center,2); var second=player(level,center,1);
        second.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(KingdomItems.KINGDOM_CHARTER.get()));
        helper.runAfterDelay(2,()-> {
            int count=SettlementSavedData.get(level.getServer()).settlements().size();
            var a=SettlementEstablishmentService.useCharter(first,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            helper.assertTrue(a.successful(),"First player establishes village: "+a.message().getString()+" / "+VillageSurvey.detect(level,center));
            var b=SettlementEstablishmentService.useCharter(second,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            var c=SettlementEstablishmentService.useCharter(second,InteractionHand.OFF_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            helper.assertTrue(!b.successful() && !c.successful() && first.getMainHandItem().getCount()==1
                    && second.getMainHandItem().getCount()==1 && second.getOffhandItem().getCount()==1
                    && SettlementSavedData.get(level.getServer()).settlements().size()==count+1,"Queued player/hand attempts create one settlement and consume one Charter");
            var mayor=NpcService.ensureMayor(level,a.settlement()).orElseThrow();
            helper.assertTrue(NpcService.ensureMayor(level,a.settlement()).orElseThrow().getUUID().equals(mayor.getUUID()),"Repeated Mayor lookup cannot duplicate NPCs");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void incompleteVillageAndConvertOnlyOutsideDoNotSpendCharters(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,122048);
        bed(level,center.offset(-8,0,3)); villager(level,center.offset(-6,0,6),false);
        var player=player(level,center,1);
        helper.runAfterDelay(2,()-> {
            int count=SettlementSavedData.get(level.getServer()).settlements().size();
            var invalid=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            helper.assertTrue(!invalid.successful() && player.getMainHandItem().getCount()==1,"Incomplete village must not fall back to destructive generation");
            var wilderness=fixture(helper,123072); player.setPos(wilderness.getX()+0.5,wilderness.getY(),wilderness.getZ()+2.5);
            var outside=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,wilderness.below(),SettlementEstablishmentService.Mode.CONVERT_ONLY);
            helper.assertTrue(!outside.successful() && player.getMainHandItem().getCount()==1
                    && SettlementSavedData.get(level.getServer()).settlements().size()==count,"Conversion-only outside a village fails without founding");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void tradersAreKeptAndConversionSpawnsFallbackMayor(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,124096); var traders=village(level,center,true,false);
        var player=player(level,center,1);
        helper.runAfterDelay(2,()-> {
            var result=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            helper.assertTrue(result.successful(),"A trading village can convert without a Town Hall: "+result.message().getString());
            var mayor=NpcService.ensureMayor(level,result.settlement()).orElseThrow();
            helper.assertTrue(!traders.contains(mayor) && traders.stream().allMatch(v -> NpcIdentity.isUnassigned(v)
                    && v.getVillagerData().getProfession()==VillagerProfession.FARMER),"Traders survive; only one new Mayor is spawned");
            helper.assertTrue(SettlementSavedData.get(level.getServer()).layout(result.settlement().id()).isEmpty(),"Conversion requires no generated core or Town Hall");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void realItemHookFoundsAndConsumesOnlyOnce(GameTestHelper helper) {
        boolean progressive=dev.livingkingdoms.config.ConstructionConfig.ENABLED.get();
        dev.livingkingdoms.config.ConstructionConfig.ENABLED.set(false);
        try {
        var level=helper.getLevel(); var center=fixture(helper,125120); var player=player(level,center,2);
        helper.assertTrue(!VillageSurvey.detect(level,center).hasSignals(),"Wilderness has no village signals");
        var context=new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(center.below()),Direction.UP,center.below(),false));
        helper.assertTrue(player.getMainHandItem().onItemUseFirst(context).consumesAction(),"Real NeoForge item-first path establishes a kingdom");
        var data=SettlementSavedData.get(level.getServer());
        var settlement=data.at(level.dimension().location().toString(),center.getX(),center.getZ()).orElseThrow();
        helper.assertTrue(settlement.provenance().origin()==SettlementOrigin.FOUNDED
                && settlement.provenance().founder().orElseThrow().equals(player.getUUID())
                && player.getMainHandItem().getCount()==1 && data.layout(settlement.id()).orElseThrow().buildings().size()>=5,"Planner core/modules, founder and exact item consumption");
        var mayor=NpcService.ensureMayor(level,settlement).orElseThrow();
        for(int i=0;i<2;i++) {
            var resident=level.getEntity(mayor.getPersistentData().getUUID("livingkingdoms:founding_resident_"+i));
            helper.assertTrue(resident instanceof Villager && NpcIdentity.read(resident).orElseThrow().role()==NpcRole.RESIDENT,"Two actual initial residents spawned");
        }
        assertInteractions(helper,player,settlement,mayor);
        player.setPos(center.getX()+0.5,center.getY(),center.getZ()+2.5);
        helper.assertTrue(player.getMainHandItem().onItemUseFirst(context)==InteractionResult.FAIL && player.getMainHandItem().getCount()==1,"Repeated item hook cannot spend or create twice");
        helper.succeed();
        } finally { dev.livingkingdoms.config.ConstructionConfig.ENABLED.set(progressive); }
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void waterFoundingFailureRetainsCharterAndReportsPlannerDiagnostics(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,126144); var player=player(level,center,1);
        for(int x=-50;x<=50;x++)for(int z=-50;z<=50;z++) level.setBlock(center.offset(x,0,z),Blocks.WATER.defaultBlockState(),18);
        int count=SettlementSavedData.get(level.getServer()).settlements().size();
        var failed=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
        helper.assertTrue(!failed.successful() && failed.diagnostics().centersChecked()>0
                && failed.diagnostics().centerFailures().containsKey(dev.livingkingdoms.structure.GenerationDiagnostics.Rejection.WATER)
                && player.getMainHandItem().getCount()==1 && SettlementSavedData.get(level.getServer()).settlements().size()==count
                && level.getBlockState(center).is(Blocks.WATER),"Existing planner refuses water without spending Charter or saving territory");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void brokenAndStaleBedPoisCannotQualifyVillage(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,127168); village(level,center,false,false);
        helper.runAfterDelay(2,()-> {
            helper.assertTrue(VillageSurvey.detect(level,center).valid(),"Initial village valid: "+VillageSurvey.detect(level,center));
            level.setBlock(center.offset(-8,0,3),Blocks.AIR.defaultBlockState(),18);
            // Retain a stale HOME record deliberately: physical head+foot validation must reject it now.
            helper.assertTrue(!VillageSurvey.detect(level,center).valid(),"An orphan bed head/POI does not count as a bed");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void unloadedSurveyAndInvalidDimensionNeverSpendCharter(GameTestHelper helper) {
        var level=helper.getLevel(); var distant=helper.absolutePos(new BlockPos(2000000,1,2000000));
        var player=player(level,distant,1);
        helper.assertTrue(!level.getChunkSource().hasChunk(distant.getX()>>4,distant.getZ()>>4),"Unloaded fixture");
        var failed=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,distant.below(),SettlementEstablishmentService.Mode.AUTO);
        helper.assertTrue(!failed.successful() && player.getMainHandItem().getCount()==1
                && !level.getChunkSource().hasChunk(distant.getX()>>4,distant.getZ()>>4),"No force-loading or partial survey establishment");
        var nether=level.getServer().getLevel(Level.NETHER); var netherPlayer=player(nether,new BlockPos(0,70,0),1);
        helper.assertTrue(!SettlementEstablishmentService.useCharter(netherPlayer,InteractionHand.MAIN_HAND,new BlockPos(0,69,0),SettlementEstablishmentService.Mode.AUTO).successful()
                && netherPlayer.getMainHandItem().getCount()==1,"Only Overworld establishments are allowed");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void canceledPlacementRollsBackWholeEstablishment(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,128192); village(level,center,false,false);
        var player=player(level,center,1);
        helper.runAfterDelay(2,()-> {
            int count=SettlementSavedData.get(level.getServer()).settlements().size();
            var plan=ConversionInfrastructure.plan(level,VillageSurvey.detect(level,center).center()).orElseThrow();
            Consumer<BlockEvent.EntityPlaceEvent> deny=event -> { if(event.getEntity()==player) event.setCanceled(true); };
            NeoForge.EVENT_BUS.addListener(deny);
            try {
                var failed=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
                helper.assertTrue(!failed.successful() && player.getMainHandItem().getCount()==1
                        && level.getBlockState(plan.marker()).isAir() && level.getBlockState(plan.board()).isAir()
                        && SettlementSavedData.get(level.getServer()).settlements().size()==count,"Protection cancellation restores blocks and reservation and retains Charter");
                var wilderness=fixture(helper,129216); player.setPos(wilderness.getX()+0.5,wilderness.getY(),wilderness.getZ()+2.5);
                var deniedFounding=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,wilderness.below(),SettlementEstablishmentService.Mode.AUTO);
                helper.assertTrue(!deniedFounding.successful() && player.getMainHandItem().getCount()==1
                        && SettlementSavedData.get(level.getServer()).settlements().size()==count
                        && level.getBlockState(wilderness.north(4)).isAir(),"Founding cancellation rolls back generated core and storage too");
            } finally { NeoForge.EVENT_BUS.unregister(deny); }
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void npcRollbackRestoresPromotedEntityAndAssociation(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,130240);
        var candidate=villager(level,center.south(6),false);
        candidate.getPersistentData();
        var original=candidate.saveWithoutId(new CompoundTag());
        var settlement=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),2,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var data=SettlementSavedData.get(level.getServer()); data.add(settlement);
        try (var transaction=NpcEstablishment.open(level,settlement,List.of(candidate))) {
            helper.assertTrue(transaction.mayor()==candidate && NpcIdentity.read(candidate).isPresent(),"Candidate promoted inside pending transaction");
        }
        helper.assertTrue(NpcIdentity.isUnassigned(candidate) && candidate.saveWithoutId(new CompoundTag()).equals(original)
                && QuestSavedData.get(level.getServer()).mayor(settlement.id()).isEmpty(),"Rollback restores complete original entity data, including absent name, and removes only new association");
        data.rollbackEstablishment(settlement);
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void refusedMayorRollsBackFoundingAndConversionWithoutSpending(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,132288); village(level,center,true,false);
        var player=player(level,center,1);
        helper.runAfterDelay(2,()-> {
            var data=SettlementSavedData.get(level.getServer()); int count=data.settlements().size();
            var plan=ConversionInfrastructure.plan(level,VillageSurvey.detect(level,center).center()).orElseThrow();
            Consumer<EntityJoinLevelEvent> deny=event -> {
                if(event.getLevel()==level && event.getEntity() instanceof Villager villager
                        && NpcIdentity.read(villager).filter(id -> id.role()==NpcRole.MAYOR).isPresent()) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(deny);
            try {
                var failed=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
                helper.assertTrue(!failed.successful() && data.settlements().size()==count && player.getMainHandItem().getCount()==1
                        && level.getBlockState(plan.marker()).isAir() && level.getBlockState(plan.board()).isAir(),"Conversion needs a Mayor and rolls back refused spawn");
                var wilderness=fixture(helper,133312); player.setPos(wilderness.getX()+0.5,wilderness.getY(),wilderness.getZ()+2.5);
                var founding=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,wilderness.below(),SettlementEstablishmentService.Mode.AUTO);
                helper.assertTrue(!founding.successful() && data.settlements().size()==count && player.getMainHandItem().getCount()==1
                        && level.getBlockState(wilderness.north(4)).isAir()
                        && !data.nearAlliedTerritory(level.dimension().location().toString(),wilderness.getX(),wilderness.getZ(),0),"Founding restores blocks, reservation and encounter exclusion when Mayor spawn fails");
            } finally { NeoForge.EVENT_BUS.unregister(deny); }
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void noMinimalPlazaPreservesVanillaVillage(GameTestHelper helper) {
        var level=helper.getLevel(); var center=fixture(helper,131264); village(level,center,false,false);
        var player=player(level,center,1);
        // Fill the entire bounded infrastructure search, leaving villagers and beds farther out intact.
        for(int x=-15;x<=15;x++)for(int z=-15;z<=15;z++)level.setBlock(center.offset(x,3,z),Blocks.OAK_PLANKS.defaultBlockState(),18);
        helper.runAfterDelay(2,()-> {
            int count=SettlementSavedData.get(level.getServer()).settlements().size();
            var result=SettlementEstablishmentService.useCharter(player,InteractionHand.MAIN_HAND,center.below(),SettlementEstablishmentService.Mode.AUTO);
            helper.assertTrue(!result.successful() && player.getMainHandItem().getCount()==1
                    && level.getBlockState(center.above(3)).is(Blocks.OAK_PLANKS)
                    && SettlementSavedData.get(level.getServer()).settlements().size()==count,"Cannot overwrite built roofs to make a conversion plaza");
            helper.succeed();
        });
    }

    private static void assertInteractions(GameTestHelper helper,UiTestPlayer player,Settlement settlement,Villager mayor) {
        player.setPos(mayor.getX()+2,mayor.getY(),mayor.getZ());
        helper.assertTrue(VillageUiService.openDialogue(player,mayor),"Mayor dialogue opens through existing UI service");
        var marker=new BlockPos(settlement.territory().x(),settlement.territory().y(),settlement.territory().z());
        var board=marker.east(settlement.provenance().origin()==SettlementOrigin.CONVERTED?2:3);
        player.setPos(board.getX()+0.5,board.getY(),board.getZ()+1.5);
        helper.assertTrue(helper.getLevel().getBlockState(board).is(KingdomBlocks.QUEST_BOARD)
                && VillageUiService.openBoard(player,board) && !QuestSavedData.get(helper.getLevel().getServer()).quests(player.getUUID(),settlement.id()).isEmpty(),"Existing board GUI and main/dynamic quests are immediately available");
    }

    private static UiTestPlayer player(ServerLevel level,BlockPos center,int charters) {
        var player=new UiTestPlayer(level); player.getAbilities().instabuild=false; player.getAbilities().mayBuild=true;
        player.setPos(center.getX()+0.5,center.getY(),center.getZ()+2.5);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(KingdomItems.KINGDOM_CHARTER.get(),charters));
        return player;
    }

    private static List<Villager> village(ServerLevel level,BlockPos center,boolean traders,boolean bell) {
        bed(level,center.offset(-8,0,3)); bed(level,center.offset(8,0,3));
        if(bell) level.setBlock(center.north(8),Blocks.BELL.defaultBlockState(),18);
        return List.of(villager(level,center.offset(-6,0,6),traders),villager(level,center.offset(6,0,6),traders));
    }

    private static Villager villager(ServerLevel level,BlockPos feet,boolean trader) {
        var villager=EntityType.VILLAGER.create(level); villager.setNoAi(true); villager.setPersistenceRequired();
        villager.setVillagerData(new VillagerData(VillagerType.PLAINS,trader?VillagerProfession.FARMER:VillagerProfession.NONE,1));
        villager.moveTo(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,0,0);
        if(!level.addFreshEntity(villager)) throw new IllegalStateException("Villager fixture refused");
        return villager;
    }

    private static void bed(ServerLevel level,BlockPos foot) {
        level.setBlock(foot,Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),18);
        level.setBlock(foot.north(),Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),18);
    }

    private static BlockPos fixture(GameTestHelper helper,int coordinate) {
        var level=helper.getLevel(); var center=helper.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=72;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++)
            for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++)level.getChunk(x,z);
        var chunk=new ChunkPos(center);
        level.getChunkSource().addRegionTicket(SURVEY_FIXTURE,chunk,6,chunk);
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);
            for(int y=-3;y<-1;y++)level.setBlock(center.offset(x,y,z),Blocks.DIRT.defaultBlockState(),18);
            for(int y=0;y<13;y++)level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        }
        return center;
    }
}

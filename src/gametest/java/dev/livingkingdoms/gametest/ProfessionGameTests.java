package dev.livingkingdoms.gametest;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import dev.livingkingdoms.ui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProfessionGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_professions_test",Comparator.comparingLong(ChunkPos::toLong),1200);
    private record Fixture(BlockPos center,Settlement settlement,UiTestPlayer player,Villager mayor) {}
    private record Worker(Citizen citizen,Villager entity) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void realCropsReplantProduceFoodXpOnceAndRespectAssignedBoundary(GameTestHelper h) {
        var f=fixture(h,260000,true); var w=worker(h,f); var level=h.getLevel(); var d=ProfessionSavedData.get(level.getServer());
        h.assertTrue(ProfessionService.assign(f.player(),f.settlement().id(),w.citizen().id()),"Assign through shared service");
        var farm=farm(f); var crops=List.of(Blocks.WHEAT,Blocks.CARROTS,Blocks.POTATOES);
        var outside=farm.origin().offset(-2,2,2); level.setBlock(outside.below(),Blocks.FARMLAND.defaultBlockState(),18);
        level.setBlock(outside,((CropBlock)Blocks.WHEAT).getStateForAge(7),18);
        for(int i=0;i<crops.size();i++) {
            var at=farm.origin().offset(2+i,2,2); var crop=(CropBlock)crops.get(i); level.setBlock(at,crop.getStateForAge(crop.getMaxAge()),18);
            w.entity().setPos(at.getX()+.5,at.getY(),at.getZ()+.5); prime(w.entity(),farm,at);
            h.assertTrue(FarmerWork.tick(w.entity()),"Real supported mature crop harvests");
            h.assertTrue(level.getBlockState(at).equals(crop.getStateForAge(0)),"Native crop replanted at age zero");
            h.assertTrue(!FarmerWork.tick(w.entity()),"Replayed work opportunity cannot duplicate food or XP");
        }
        var second=worker(h,f); h.assertTrue(ProfessionService.assign(f.player(),f.settlement().id(),second.citizen().id()),"A second real worker shares the same Farm");
        var harvested=farm.origin().offset(4,2,2); second.entity().setPos(harvested.getX()+.5,harvested.getY(),harvested.getZ()+.5); prime(second.entity(),farm,harvested);
        h.assertTrue(!FarmerWork.tick(second.entity()) && d.profession(second.citizen().id()).orElseThrow().experience()==0,"Another worker cannot reward the same already-replanted crop");
        h.assertTrue(d.food(f.settlement().id(),500).stock()==6 && d.profession(w.citizen().id()).orElseThrow().experience()==15,"Only meaningful work contributes food and profession XP");
        h.assertTrue(level.getBlockState(outside).equals(((CropBlock)Blocks.WHEAT).getStateForAge(7)),"Player crops outside workplace stay untouched");
        h.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(farm.origin()).inflate(12)).isEmpty(),"Abstract Food creates no duplicated item drops");
        var unrelated=EntityType.VILLAGER.create(level); unrelated.moveTo(harvested.getX()+.5,harvested.getY(),harvested.getZ()+.5,0,0);
        h.assertTrue(!FarmerWork.tick(unrelated),"Unregistered villagers cannot enter the profession work loop");
        var people=CitizenSavedData.get(level.getServer()); var citizen=people.citizen(w.citizen().id()).orElseThrow();
        var loaded=ProfessionSavedData.load(d.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        h.assertTrue(loaded.profession(citizen.id()).orElseThrow().equals(d.profession(citizen.id()).orElseThrow())
                && citizen.homeId().equals(w.citizen().homeId()) && citizen.level().equals(w.citizen().level()),"Home identity and separate shared level survive farmer work and profile reload"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void farmerUsesNativeNavigationToReachWorkplaceAndHarvest(GameTestHelper h) {
        var f=fixture(h,261024,true); var w=worker(h,f); var farm=farm(f); var at=farm.origin().offset(2,2,2);
        h.getLevel().setBlock(at,((CropBlock)Blocks.WHEAT).getStateForAge(7),18); w.entity().setPos(at.getX()+.5,at.getY()-1,at.getZ()-12.5);
        h.assertTrue(ProfessionService.assign(f.player(),f.settlement().id(),w.citizen().id()),"Loaded citizen is assigned before navigation"); prime(w.entity(),farm,at);
        var initial=w.entity().position(); FarmerWork.tick(w.entity()); h.assertTrue(w.entity().position().equals(initial),"Starting work never teleports a citizen");
        var sawPath=new java.util.concurrent.atomic.AtomicBoolean();
        h.succeedWhen(()-> {
            h.getLevel().setDayTime(6000); FarmerWork.tick(w.entity()); if(!w.entity().getNavigation().isDone()) sawPath.set(true);
            var profile=ProfessionSavedData.get(h.getLevel().getServer()).profession(w.citizen().id()).orElseThrow();
            h.assertTrue(ProfessionService.food(h.getLevel().getServer(),f.settlement().id()).stock()>0,"Loaded worker eventually navigates and harvests; ticks="+w.entity().tickCount+", ground="+w.entity().onGround()+", position="+w.entity().position()+", pathDone="+w.entity().getNavigation().isDone()+", state="+profile.workState()+", failures="+profile.navigationFailures());
            h.assertTrue(sawPath.get() && initial.distanceToSqr(w.entity().position())>4 && h.getLevel().getBlockState(at).equals(((CropBlock)Blocks.WHEAT).getStateForAge(0)),"Entity actually traveled and replanted");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void sharedGuiGuardsNonceCapacityReplayAndFirstProfessionAdvancement(GameTestHelper h) {
        var f=fixture(h,262048,true); var a=worker(h,f); var b=worker(h,f); var c=worker(h,f); var second=player(h.getLevel(),f.mayor().blockPosition().east(2));
        VillageUiService.openDialogue(f.player(),f.mayor()); VillageUiService.openDialogue(second,f.mayor());
        UUID one=f.player().snapshots.getLast().getUUID("session"),two=second.snapshots.getLast().getUUID("session");
        h.assertTrue(!VillageUiService.handle(f.player(),request(one,a,UiPayloads.Action.ASSIGN_FARMER)),"Assignment needs Citizens view");
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(one,new UUID(0,0),UiPayloads.Action.CITIZENS))
                && VillageUiService.handle(second,new UiPayloads.Request(two,new UUID(0,0),UiPayloads.Action.CITIZENS)),"Normal Mayor navigation exposes roster");
        var snapshot=f.player().snapshots.getLast(); h.assertTrue(snapshot.getString("screen").equals("citizens") && snapshot.getList("citizens",10).size()==4,"One Mayor and three real citizens shown");
        h.assertTrue(!VillageUiService.handle(second,request(one,a,UiPayloads.Action.ASSIGN_FARMER)),"Foreign nonce rejects forged packet");
        h.assertTrue(VillageUiService.handle(f.player(),request(one,a,UiPayloads.Action.ASSIGN_FARMER)),"First shared assignment succeeds");
        h.assertTrue(!VillageUiService.handle(second,request(two,a,UiPayloads.Action.ASSIGN_FARMER)),"Same citizen cannot race into another assignment");
        h.assertTrue(VillageUiService.handle(second,request(two,b,UiPayloads.Action.ASSIGN_FARMER)) && !VillageUiService.handle(f.player(),request(one,c,UiPayloads.Action.ASSIGN_FARMER)),"Two slots fill; third assignment gets current rejection snapshot");
        h.assertTrue(f.player().snapshots.getLast().getString("notice").endsWith("rejected"),"Clear server feedback on full workplace");
        var advancement=h.getLevel().getServer().getAdvancements().get(KingdomMilestone.FIRST_PROFESSION.id());
        h.assertTrue(advancement!=null && f.player().getAdvancements().getOrStartProgress(advancement).isDone()
                && second.getAdvancements().getOrStartProgress(advancement).isDone() && !KingdomMilestone.awardFirstProfession(f.player()),"Each assigning player earns the native milestone once");
        h.assertTrue(VillageUiService.handle(f.player(),request(one,a,UiPayloads.Action.REMOVE_PROFESSION)) && !VillageUiService.handle(f.player(),request(one,a,UiPayloads.Action.REMOVE_PROFESSION)),"Removal releases one slot once");
        h.assertTrue(VillageUiService.handle(second,request(two,c,UiPayloads.Action.ASSIGN_FARMER)),"Another player can reuse the released slot");
        f.player().setPos(f.center().getX()+100,f.center().getY(),f.center().getZ());
        h.assertTrue(!VillageUiService.handle(f.player(),request(one,b,UiPayloads.Action.REMOVE_PROFESSION)),"Moving away invalidates management capability"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void nativeFarmConstructionRegistersWorkplacesUsingExistingProjectReceipt(GameTestHelper h) {
        var f=fixture(h,263072,false); var server=h.getLevel().getServer();
        VillageUiService.openDialogue(f.player(),f.mayor()); var token=f.player().snapshots.getLast().getUUID("session");
        VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.CONSTRUCTION));
        h.assertTrue(VillageUiService.handle(f.player(),new UiPayloads.Request(token,new UUID(0,0),UiPayloads.Action.PLAN_FARM)),"Plan Farm from existing construction UI");
        var entry=ConstructionService.current(server,f.settlement().id()).orElseThrow(); var project=entry.project();
        h.assertTrue(project.building()==BuildingKind.FARM && project.durationTicks()>0 && !project.required().isEmpty(),"Farm requires resources and time");
        h.assertTrue(!ConstructionService.planFarm(h.getLevel(),f.settlement().id(),f.player()),"One current shared project prevents competing plans");
        f.player().getInventory().items.set(0,new ItemStack(Items.OAK_LOG,64)); f.player().getInventory().items.set(1,new ItemStack(Items.STONE,64)); f.player().getInventory().items.set(2,new ItemStack(Items.IRON_INGOT,64));
        h.assertTrue(ConstructionService.deposit(f.player(),f.settlement().id(),project.id()),"Normal delivery funds the Farm");
        h.assertTrue(!ConstructionService.resolve(server,project.id(),f.player()),"Time gates normal completion");
        h.assertTrue(ConstructionService.resolve(server,project.id(),f.player(),true) && !ConstructionService.resolve(server,project.id(),f.player(),true),"Development completion still uses one native placement receipt");
        var farm=farm(f); var d=ProfessionSavedData.get(server);
        h.assertTrue(d.workers(farm.id())==0 && farm.workplaceSlots()==2 && farm.capabilities().contains(BuildingCapability.FOOD_PRODUCTION),"Registered Farm makes configured slots available");
        var water=entry.plan().buildings().getFirst().position(new BlockPos(4,1,3)); h.assertTrue(h.getLevel().getBlockState(water).is(Blocks.WATER),"Adaptive native Farm contains irrigation");
        h.assertTrue(ConstructionSavedData.get(server).reservations(f.settlement().id()).isEmpty(),"Completed project releases plot reservation"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void farmerDeathReleasesWorkplaceAndKeepsHistoryWithoutReplacement(GameTestHelper h) {
        var f=fixture(h,264096,true); var w=worker(h,f); var server=h.getLevel().getServer(); var people=CitizenSavedData.get(server); var d=ProfessionSavedData.get(server);
        ProfessionService.assign(f.player(),f.settlement().id(),w.citizen().id()); int count=people.population(f.settlement().id());
        w.entity().hurt(h.getLevel().damageSources().genericKill(),1000);
        h.assertTrue(people.citizen(w.citizen().id()).orElseThrow().state()==CitizenState.DEAD && people.population(f.settlement().id())==count-1
                && d.workers(farm(f).id())==0 && !d.profession(w.citizen().id()).orElseThrow().active(),"Confirmed death updates population/housing/workplace together");
        ProfessionService.ensure(h.getLevel(),f.settlement());
        h.assertTrue(people.citizens(f.settlement().id()).size()==count && d.building(farm(f).id()).isPresent()
                && !ProfessionService.assign(f.player(),f.settlement().id(),w.citizen().id()),"Historical identity never respawns; Farm remains"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void protectionStorageAndNightScheduleNeverProduceUnworkedFood(GameTestHelper h) {
        var f=fixture(h,265120,true); var w=worker(h,f); var level=h.getLevel(); var farm=farm(f); var at=farm.origin().offset(2,2,2); var data=ProfessionSavedData.get(level.getServer());
        ProfessionService.assign(f.player(),f.settlement().id(),w.citizen().id()); w.entity().setPos(at.getX()+.5,at.getY(),at.getZ()+.5); level.setBlock(at,((CropBlock)Blocks.WHEAT).getStateForAge(7),18);
        boolean grief=level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        try { level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer()); prime(w.entity(),farm,at); h.assertTrue(!FarmerWork.tick(w.entity()),"mobGriefing=false forbids harvesting"); }
        finally { level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief,level.getServer()); }
        Consumer<BlockEvent.EntityPlaceEvent> deny=event -> { if(event.getEntity()==w.entity()) event.setCanceled(true); };
        NeoForge.EVENT_BUS.addListener(deny);
        try { prime(w.entity(),farm,at); h.assertTrue(!FarmerWork.tick(w.entity()) && level.getBlockState(at).equals(((CropBlock)Blocks.WHEAT).getStateForAge(7)),"Canceled replant restores mature crop without reward"); }
        finally { NeoForge.EVENT_BUS.unregister(deny); }
        h.assertTrue(data.food(f.settlement().id(),500).stock()==0 && data.profession(w.citizen().id()).orElseThrow().experience()==0,"Denied attempts grant neither food nor XP");
        prime(w.entity(),farm,at); level.setDayTime(18000); h.assertTrue(!FarmerWork.tick(w.entity()),"No night farming");
        h.assertTrue(data.profession(w.citizen().id()).orElseThrow().workState()!=WorkState.WORKING,"Night work state returns home or waits safely");
        data.addFood(f.settlement().id(),500); prime(w.entity(),farm,at); h.assertTrue(!FarmerWork.tick(w.entity()) && data.profession(w.citizen().id()).orElseThrow().workState()==WorkState.STORAGE_FULL,"Full storage pauses physical work"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void convertedTradesStayIntactAndUnassignedVillagersNeverJoinWorkLoop(GameTestHelper h) {
        var f=fixture(h,266144,true); var w=worker(h,f); var farm=farm(f); var at=farm.origin().offset(2,2,2); var level=h.getLevel();
        w.entity().setVillagerData(w.entity().getVillagerData().setProfession(VillagerProfession.FARMER).setLevel(2)); w.entity().setVillagerXp(20);
        var offers=w.entity().getOffers().copy(); var savedOffers=w.entity().saveWithoutId(new CompoundTag()).getCompound("Offers").copy(); var career=w.entity().getVillagerData(); var home=w.citizen().homeId();
        level.setBlock(at,((CropBlock)Blocks.WHEAT).getStateForAge(7),18); w.entity().setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        h.assertTrue(!FarmerWork.tick(w.entity()) && ProfessionSavedData.get(level.getServer()).profession(w.citizen().id()).orElseThrow().type()==ProfessionType.UNASSIGNED,"Vanilla Farmer career is not automatically an LK worker");
        h.assertTrue(ProfessionService.assign(f.player(),f.settlement().id(),w.citizen().id()),"Explicit assignment enables bounded LK work"); var workerBrain=w.entity().getBrain(); prime(w.entity(),farm,at); FarmerWork.tick(w.entity());
        h.assertTrue(ProfessionService.remove(f.player(),f.settlement().id(),w.citizen().id()),"Safe removal restores vanilla Brain");
        h.assertTrue(w.entity().getVillagerData().equals(career) && w.entity().getVillagerXp()==20 && w.entity().getOffers().size()==offers.size() && w.entity().saveWithoutId(new CompoundTag()).getCompound("Offers").equals(savedOffers)
                && CitizenSavedData.get(level.getServer()).citizen(w.citizen().id()).orElseThrow().homeId().equals(home),"Trades, vanilla career level/XP, identity and home remain intact");
        h.assertTrue(w.entity().getBrain()!=workerBrain,"Vanilla behaviors are restored after profession removal"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void unloadedFarmDoesNotCreateChunksAndResumesAfterNativeEntityReload(GameTestHelper h) {
        var f=fixture(h,267168,false); var level=h.getLevel(); var server=level.getServer(); var center=f.center().offset(5000,0,5000);
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),48),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var building=description(BuildingCatalog.load(level,ArchitectureStyle.PLAINS).get(BuildingKind.FARM),center.offset(-12,-1,0));
        var house=description(BuildingCatalog.load(level,ArchitectureStyle.PLAINS).get(BuildingKind.HOUSE),center.offset(12,-1,0));
        var layout=new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(building,house),List.of(center.below()),List.of());
        SettlementSavedData.get(server).add(s,layout); var people=CitizenSavedData.get(server); people.synchronizeHousing(s,layout,b -> 2);
        var entity=EntityType.VILLAGER.create(level); entity.moveTo(f.center().getX(),f.center().getY(),f.center().getZ(),0,0);
        var citizen=new Citizen(UUID.randomUUID(),entity.getUUID(),s.id(),"Mira Ashbrook",new LevelValue(1),CitizenRole.UNASSIGNED,people.houses(s.id()).getFirst().id(),CitizenState.ACTIVE,0);
        people.register(citizen); CitizenService.apply(citizen,entity); level.addFreshEntity(entity);
        var d=ProfessionSavedData.get(server); d.synchronize(s,layout,2,List.of(citizen)); d.ensureFood(s.id(),500); var farm=d.buildings(s.id()).stream().filter(b -> b.kind()==BuildingKind.FARM).findFirst().orElseThrow(); d.assignFarmer(citizen,farm.id(),0);
        level.setDayTime(6000); h.assertTrue(!level.getChunkSource().hasChunk(farm.origin().getX()>>4,farm.origin().getZ()>>4),"Fixture Farm begins unloaded");
        h.assertTrue(!FarmerWork.tick(entity) && d.profession(citizen.id()).orElseThrow().workState()==WorkState.UNLOADED
                && !level.getChunkSource().hasChunk(farm.origin().getX()>>4,farm.origin().getZ()>>4) && d.food(s.id(),500).stock()==0,"Work check never loads or simulates absent chunks");
        var saved=entity.saveWithoutId(new CompoundTag()); entity.discard();
        // Test-only arrival of the chunk and same entity UUID; production never issues these tickets.
        for(int x=farm.bounds().minX()>>4;x<=farm.bounds().maxX()>>4;x++) for(int z=farm.bounds().minZ()>>4;z<=farm.bounds().maxZ()>>4;z++) level.getChunk(x,z);
        var at=farm.origin().offset(2,2,2); level.setBlock(at.below(),Blocks.FARMLAND.defaultBlockState(),18); level.setBlock(at,((CropBlock)Blocks.WHEAT).getStateForAge(7),18);
        var loaded=EntityType.VILLAGER.create(level); loaded.load(saved); loaded.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0); level.addFreshEntity(loaded);
        FarmerWork.onJoin(new EntityJoinLevelEvent(loaded,level)); prime(loaded,farm,at);
        h.assertTrue(FarmerWork.tick(loaded) && loaded.getUUID().equals(citizen.entityId()) && people.population(s.id())==1,"Same loaded worker resumes without replacement entity"); h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void foodShortageUsesExistingQuestReceiptAndImmigrationModifier(GameTestHelper h) {
        var f=fixture(h,268192,true); var level=h.getLevel(); var server=level.getServer(); var data=ProfessionSavedData.get(server);
        h.assertTrue(ProfessionService.immigrationModifier(server,f.settlement().id())==.1 && ProfessionService.shortageWeights(server,f.settlement().id()).get(ResourceKind.WHEAT)==12,"Low food reduces immigration and supplies dynamic shortage condition");
        var board=f.center().east(3); level.setBlock(board,KingdomBlocks.QUEST_BOARD.get().defaultBlockState(),18); f.player().setPos(board.getX()+.5,board.getY(),board.getZ()+1.5);
        ExpandedQuestService.prepare(f.player(),f.settlement());
        var quest=new QuestInstance(UUID.randomUUID(),QuestTemplate.FOOD_REQUEST,new QuestSource(f.settlement().id(),null,QuestSourceRole.FARMER),
                new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.WHEAT,40))),new LevelValue(1),QuestDifficulty.VERY_EASY,new QuestRewards(1,1),QuestState.AVAILABLE,false,ImmigrationService.now(server),ImmigrationService.now(server)+24000);
        var quests=QuestSavedData.get(server); h.assertTrue(quests.offer(f.player().getUUID(),quest),"Existing board stores food request");
        f.player().getInventory().items.set(0,new ItemStack(Items.WHEAT,40)); h.assertTrue(ExpandedQuestService.act(f.player(),board,quest.id(),"accept",false) && ExpandedQuestService.act(f.player(),board,quest.id(),"claim",false),"Existing delivery and claim receipt contributes real Food");
        h.assertTrue(!ExpandedQuestService.act(f.player(),board,quest.id(),"claim",false) && data.food(f.settlement().id(),500).stock()==80,"Replay cannot add food twice");
        h.assertTrue(ProfessionService.immigrationModifier(server,f.settlement().id())==1 && ProfessionService.shortageWeights(server,f.settlement().id()).isEmpty(),"Healthy food restores normal chance and clears shortage condition"); h.succeed();
    }

    private static UiPayloads.Request request(UUID token,Worker w,UiPayloads.Action action) { return new UiPayloads.Request(token,w.citizen().id(),action); }
    private static FunctionalBuilding farm(Fixture f) { return ProfessionSavedData.get(f.player().server).buildings(f.settlement().id()).stream().filter(b -> b.kind()==BuildingKind.FARM).findFirst().orElseThrow(); }
    private static void prime(Villager v,FunctionalBuilding farm,BlockPos at) {
        var d=ProfessionSavedData.get(v.level().getServer()); var c=CitizenSavedData.get(v.level().getServer()).byEntity(v.getUUID()).orElseThrow(); var p=d.profession(c.id()).orElseThrow();
        int cursor=0; while(!farm.cropPosition(cursor).equals(at)) cursor++;
        d.replace(p,p.work(WorkState.IDLE,0,cursor,0)); ((ServerLevel)v.level()).setDayTime(6000);
    }
    private static Worker worker(GameTestHelper h,Fixture f) {
        var candidate=ImmigrationService.attempt(h.getLevel(),f.settlement(),true).orElseThrow(); h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Actual immigrant accepted into free home");
        var c=CitizenSavedData.get(h.getLevel().getServer()).citizen(candidate.id()).orElseThrow(); ProfessionService.ensure(h.getLevel(),f.settlement()); return new Worker(c,(Villager)h.getLevel().getEntity(c.entityId()));
    }
    private static UiTestPlayer player(ServerLevel level,BlockPos pos) {
        var p=new UiTestPlayer(level) { @Override public boolean isFakePlayer() { return false; } }; p.getAbilities().mayBuild=true; p.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5); return p;
    }
    private static SettlementLayoutMetadata.Building description(BuildingTemplate m,BlockPos origin) {
        return new SettlementLayoutMetadata.Building(m.kind(),m.id(),origin,Rotation.NONE,new PlotBounds(origin.getX(),origin.getZ(),origin.getX()+m.size().getX()-1,origin.getZ()+m.size().getZ()-1),origin.offset(m.entrance()));
    }
    private static Fixture fixture(GameTestHelper h,int coordinate,boolean farm) {
        var level=h.getLevel(); var center=h.absolutePos(new BlockPos(coordinate,1,coordinate)); int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center); level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) { level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18); for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18); }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var buildings=new ArrayList<SettlementLayoutMetadata.Building>(); var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        for(int i=0;i<(farm?3:2);i++) {
            var m=catalog.get(i==2?BuildingKind.FARM:BuildingKind.HOUSE); var at=center.offset(i==2?-18:12,-1,i==1?-16:12);
            m.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18); buildings.add(description(m,at));
        }
        var settlements=SettlementSavedData.get(level.getServer()); settlements.add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of())); level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,s).orElseThrow(); ProfessionService.ensure(level,s); level.setDayTime(6000); return new Fixture(center,s,player(level,mayor.blockPosition().east(2)),mayor);
    }
}

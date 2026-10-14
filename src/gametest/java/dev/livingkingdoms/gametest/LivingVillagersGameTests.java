package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.*;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingVillagersGameTests {
    private static final TicketType<ChunkPos> FIXTURE=TicketType.create("livingkingdoms_v017_workers",Comparator.comparingLong(ChunkPos::toLong),800);
    private record Fixture(Settlement settlement,BlockPos center,UiTestPlayer player) {}
    private record Worker(Citizen citizen,Villager entity) {}

    @GameTest(template="empty",timeoutTicks=600)
    public static void lumberjackMinerAndBlacksmithProducePaidStockXpAndPersistReceipts(GameTestHelper h) {
        var f=fixture(h,950000);var server=h.getLevel().getServer();fill(f);var jobs=ProfessionSavedData.get(server);
        int food=KingdomEconomy.count(server,f.settlement().id(),StockResource.FOOD);
        for(var type:List.of(ProfessionType.LUMBERJACK,ProfessionType.MINER,ProfessionType.BLACKSMITH)) {
            var w=worker(h,f);var mayor=h.getLevel().getEntity(dev.livingkingdoms.quest.persistence.QuestSavedData.get(server).mayor(f.settlement().id()).orElseThrow());
            dev.livingkingdoms.ui.VillageUiService.openDialogue(f.player(),(Villager)mayor);var session=f.player().snapshots.getLast().getUUID("session");
            dev.livingkingdoms.ui.VillageUiService.handle(f.player(),new dev.livingkingdoms.ui.UiPayloads.Request(session,new UUID(0,0),dev.livingkingdoms.ui.UiPayloads.Action.CITIZENS));
            var action=switch(type) {case LUMBERJACK->dev.livingkingdoms.ui.UiPayloads.Action.ASSIGN_LUMBERJACK;case MINER->dev.livingkingdoms.ui.UiPayloads.Action.ASSIGN_MINER;default->dev.livingkingdoms.ui.UiPayloads.Action.ASSIGN_BLACKSMITH;};
            h.assertTrue(dev.livingkingdoms.ui.VillageUiService.handle(f.player(),new dev.livingkingdoms.ui.UiPayloads.Request(session,w.citizen().id(),action)),"Actual Mayor session assigns each new profession");
            var p=jobs.profession(w.citizen().id()).orElseThrow();var building=jobs.building(p.workplaceId()).orElseThrow();place(w.entity(),building.entrance().above());
            h.assertTrue(EconomicWork.tick(w.entity()),"Loaded adult at a real station produces a paid batch");
            var actual=jobs.profession(p.citizenId()).orElseThrow();h.assertTrue(actual.experience()==5 && actual.nextWorkAt()>server.overworld().getGameTime(),"Real work advances career and persistent cooldown");
            h.assertTrue(!EconomicWork.tick(w.entity()),"Repeated call cannot duplicate production");
            var saved=w.entity().saveWithoutId(new CompoundTag());w.entity().discard();var loaded=EntityType.VILLAGER.create(h.getLevel());loaded.load(saved);h.getLevel().addFreshEntity(loaded);
            h.assertTrue(loaded.getUUID().equals(w.citizen().entityId()) && !EconomicWork.tick(loaded),"Native entity reload cannot reset stock receipt or identity");
            h.assertTrue(ProfessionService.remove(f.player(),f.settlement().id(),w.citizen().id()) && loaded.getMainHandItem().isEmpty(),"Removal restores vanilla citizen and removes only issued tool");
            h.assertTrue(ProfessionService.assignEconomic(f.player(),f.settlement().id(),w.citizen().id(),type) && jobs.profession(w.citizen().id()).orElseThrow().experience()==5,"Reassignment retains separate profession career");
            h.assertTrue(!EconomicWork.tick(loaded),"Removing and reassigning cannot skip a persisted production cooldown");
        }
        h.assertTrue(KingdomEconomy.count(server,f.settlement().id(),StockResource.FOOD)==food-5,"All batches consume actual food");
        var stock=KingdomEconomy.ensure(server,f.settlement().id());h.assertTrue(stock.produced().get(StockResource.WOOD)==4L && stock.produced().get(StockResource.STONE)==4L
                && stock.produced().get(StockResource.IRON)==1L && stock.produced().get(StockResource.EQUIPMENT)==1L,"Production totals exclude contributed materials");
        var reopened=EconomySavedData.load(EconomySavedData.get(server).save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(reopened.account(f.settlement().id()).orElseThrow().equals(stock),"Shared economic state survives native NBT reload");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=650)
    public static void economicWorkerReachesWorkplaceThroughNativeNavigation(GameTestHelper h) {
        var f=fixture(h,951024);fill(f);var w=worker(h,f);var server=h.getLevel().getServer();
        h.assertTrue(ProfessionService.assignEconomic(f.player(),f.settlement().id(),w.citizen().id(),ProfessionType.LUMBERJACK),"Worker assigned");
        place(w.entity(),f.center().east(2));var initial=w.entity().position();EconomicWork.tick(w.entity());h.assertTrue(initial.equals(w.entity().position()),"Work never teleports a citizen");
        var sawPath=new java.util.concurrent.atomic.AtomicBoolean();
        h.succeedWhen(()-> {
            h.getLevel().setDayTime(6000);EconomicWork.tick(w.entity());if(!w.entity().getNavigation().isDone()) sawPath.set(true);
            h.assertTrue(KingdomEconomy.ensure(server,f.settlement().id()).produced().getOrDefault(StockResource.WOOD,0L)==4L,"Path arrives and one real batch produces wood");
            var p=ProfessionSavedData.get(server).profession(w.citizen().id()).orElseThrow();var building=ProfessionSavedData.get(server).building(p.workplaceId()).orElseThrow();
            h.assertTrue(sawPath.get() && initial.distanceToSqr(w.entity().position())>4 && w.entity().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(building.entrance().above()))<16,"Worker traveled on a native path to its workplace");
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void missingStationInputsAndUnloadedHomeNeverProduceOrLoadChunks(GameTestHelper h) {
        var f=fixture(h,952048);var w=worker(h,f);var level=h.getLevel();var jobs=ProfessionSavedData.get(level.getServer());
        h.assertTrue(ProfessionService.assignEconomic(f.player(),f.settlement().id(),w.citizen().id(),ProfessionType.MINER),"Miner assigned");
        var p=jobs.profession(w.citizen().id()).orElseThrow();var building=jobs.building(p.workplaceId()).orElseThrow();place(w.entity(),building.entrance().above());
        h.assertTrue(!EconomicWork.tick(w.entity()) && jobs.profession(p.citizenId()).orElseThrow().workState()==WorkState.MISSING_INPUTS,"No free production when inputs are absent");
        fill(f);prime(jobs,p.citizenId(),level);
        var module=WorkSites.template(level,building);var station=module.worldPosition(new BlockPos(2,1,module.size().getZ()-3),building.origin(),building.rotation());level.setBlock(station,Blocks.AIR.defaultBlockState(),18);
        h.assertTrue(!EconomicWork.tick(w.entity()) && jobs.profession(p.citizenId()).orElseThrow().workState()==WorkState.BLOCKED,"Missing physical station blocks work");
        h.assertTrue(KingdomEconomy.ensure(level.getServer(),f.settlement().id()).produced().isEmpty(),"Failed attempts leave stock counters untouched");
        var remote=f.center().offset(5000,0,5000);var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),remote.getX(),remote.getY(),remote.getZ(),48),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);var house=description(catalog.get(BuildingKind.HOUSE),remote.offset(12,-1,0));var mine=description(catalog.get(BuildingKind.MINE),remote.offset(-16,-1,0));
        var layout=new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(house,mine),List.of(remote.below()),List.of());SettlementSavedData.get(level.getServer()).add(s,layout);
        var people=CitizenSavedData.get(level.getServer());people.synchronizeHousing(s,layout,b->2);var ghost=EntityType.VILLAGER.create(level);place(ghost,f.center());
        var citizen=new Citizen(UUID.randomUUID(),ghost.getUUID(),s.id(),"Unloaded worker",new dev.livingkingdoms.progression.domain.LevelValue(1),CitizenRole.UNASSIGNED,people.houses(s.id()).getFirst().id(),CitizenState.ACTIVE,0);
        people.register(citizen);CitizenService.apply(citizen,ghost);level.addFreshEntity(ghost);jobs.synchronize(s,layout,2,2,2,List.of(citizen),2,Map.of());
        var remoteMine=jobs.buildings(s.id()).stream().filter(b->b.kind()==BuildingKind.MINE).findFirst().orElseThrow();jobs.assignEconomic(citizen,remoteMine.id(),0,ProfessionType.MINER);
        h.assertTrue(!level.getChunkSource().hasChunk(remote.getX()>>4,remote.getZ()>>4) && !EconomicWork.tick(ghost)
                && !level.getChunkSource().hasChunk(remote.getX()>>4,remote.getZ()>>4),"Unloaded home and workplace checks never force chunk loading");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void workersSleepInOwnHomeWakeAndShelterWithoutProducing(GameTestHelper h) {
        var f=fixture(h,953072);fill(f);var w=worker(h,f);var level=h.getLevel();var jobs=ProfessionSavedData.get(level.getServer());
        h.assertTrue(ProfessionService.assignEconomic(f.player(),f.settlement().id(),w.citizen().id(),ProfessionType.LUMBERJACK),"Worker assigned");
        var home=CitizenSavedData.get(level.getServer()).housing(w.citizen().homeId()).orElseThrow();BlockPos bed=null;
        for(int x=0;x<9;x++) for(int z=0;z<9;z++) {var pos=home.position().offset(x,1,z);var state=level.getBlockState(pos);if(state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART)==net.minecraft.world.level.block.state.properties.BedPart.HEAD) bed=pos;}
        h.assertTrue(bed!=null,"Assigned native home contains a bed");place(w.entity(),bed);long time=level.getDayTime();
        try {
            level.setDayTime(18000);h.assertTrue(!EconomicWork.tick(w.entity()) && w.entity().isSleeping(),"Night work returns to the citizen's own bed and sleeps");
            level.setDayTime(6000);EconomicWork.tick(w.entity());h.assertTrue(!w.entity().isSleeping(),"Morning wakes the same worker");
            prime(jobs,w.citizen().id(),level);var p=jobs.profession(w.citizen().id()).orElseThrow();var stateBefore=KingdomEconomy.ensure(level.getServer(),f.settlement().id());
            w.entity().hurt(w.entity().damageSources().generic(),1);h.assertTrue(!EconomicWork.tick(w.entity()) && jobs.profession(p.citizenId()).orElseThrow().workState()==WorkState.FLEEING
                    && KingdomEconomy.ensure(level.getServer(),f.settlement().id()).equals(stateBefore),"Recent damage triggers refuge without production");
        } finally {level.setDayTime(time);if(w.entity().isSleeping()) w.entity().stopSleeping();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void smithRepairsHeldGearAndForgedGuardArmorSpendsOnce(GameTestHelper h) {
        var f=fixture(h,954096);fill(f);var level=h.getLevel();var server=level.getServer();var jobs=ProfessionSavedData.get(server);var smith=worker(h,f);
        h.assertTrue(ProfessionService.assignEconomic(f.player(),f.settlement().id(),smith.citizen().id(),ProfessionType.BLACKSMITH),"Smith assigned");
        var p=jobs.profession(smith.citizen().id()).orElseThrow();place(smith.entity(),jobs.building(p.workplaceId()).orElseThrow().entrance().above());
        h.assertTrue(EconomicWork.tick(smith.entity()),"Smith produces a real plate");var guard=worker(h,f);h.assertTrue(ProfessionService.assignGuard(f.player(),f.settlement().id(),guard.citizen().id()),"Guard assigned");
        h.assertTrue(GuardEquipment.forge(f.player(),f.settlement().id(),guard.citizen().id()) && !GuardEquipment.forge(f.player(),f.settlement().id(),guard.citizen().id()),"Only one plate is spent for a real guard upgrade");
        GuardEquipment.apply(guard.entity(),jobs.profession(guard.citizen().id()).orElseThrow());h.assertTrue(guard.entity().getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),"Routine low-level equipment refresh preserves paid iron plate");
        var stranger=new UiTestPlayer(level);place(stranger,f.center());h.assertTrue(!GuardEquipment.forge(stranger,f.settlement().id(),guard.citizen().id()),"Unjoined player cannot equip guards from shared stock");
        var sword=new ItemStack(Items.IRON_SWORD);sword.setDamageValue(80);f.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,sword);prime(jobs,p.citizenId(),level);
        smith.entity().setNoAi(true);int unchangedIron=KingdomEconomy.count(server,f.settlement().id(),StockResource.IRON);
        h.assertTrue(!EconomicWork.repairHeld(f.player(),f.settlement().id()) && sword.getDamageValue()==80 && KingdomEconomy.count(server,f.settlement().id(),StockResource.IRON)==unchangedIron,"Disabled AI cannot silently perform forge work or spend stock");smith.entity().setNoAi(false);
        int iron=KingdomEconomy.count(server,f.settlement().id(),StockResource.IRON);h.assertTrue(EconomicWork.repairHeld(f.player(),f.settlement().id()) && sword.getDamageValue()==40
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.IRON)==iron-1,"Real smith repair preserves stack and spends existing iron/food");
        h.assertTrue(!EconomicWork.repairHeld(f.player(),f.settlement().id()),"Repair cannot bypass production cooldown");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void builderStructuralRepairsRespectProtectionAndNeverOverwritePlayerBlocks(GameTestHelper h) {
        var f=fixture(h,955120);fill(f);var level=h.getLevel();var server=level.getServer();var jobs=ProfessionSavedData.get(server);var w=worker(h,f);
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Existing Builder assigned");
        var building=jobs.buildings(f.settlement().id()).stream().filter(b->b.kind()==BuildingKind.LUMBERJACK_HUT).findFirst().orElseThrow();place(w.entity(),building.entrance().above());
        var module=WorkSites.template(level,building);var originals=module.blocks().stream().filter(b->b.pos().getY()==1 && RepairWork.structural(b.state())).limit(2).toList();
        var missing=module.worldPosition(originals.getFirst().pos(),building.origin(),building.rotation());var modified=module.worldPosition(originals.getLast().pos(),building.origin(),building.rotation());
        level.setBlock(missing,Blocks.AIR.defaultBlockState(),18);level.setBlock(modified,Blocks.DIAMOND_BLOCK.defaultBlockState(),18);
        h.assertTrue(BuildingDevelopment.repair(f.player(),f.settlement().id(),building.id()),"Owner explicitly queues structural maintenance");
        int wood=KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD),stone=KingdomEconomy.count(server,f.settlement().id(),StockResource.STONE);
        Consumer<BlockEvent.EntityPlaceEvent> protect=event->{if(event.getEntity()==w.entity()) event.setCanceled(true);};NeoForge.EVENT_BUS.addListener(protect);
        try {for(int i=0;i<100;i++) {prime(jobs,w.citizen().id(),level);RepairWork.tick(w.entity(),jobs.profession(w.citizen().id()).orElseThrow());if(jobs.profession(w.citizen().id()).orElseThrow().workState()==WorkState.BLOCKED) break;}}
        finally {NeoForge.EVENT_BUS.unregister(protect);}
        h.assertTrue(level.getBlockState(missing).isAir() && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==wood && KingdomEconomy.count(server,f.settlement().id(),StockResource.STONE)==stone,"Canceled placement restores world and spends no resources");
        prime(jobs,w.citizen().id(),level);h.assertTrue(RepairWork.tick(w.entity(),jobs.profession(w.citizen().id()).orElseThrow()),"Repair succeeds when protection permits");
        h.assertTrue(!level.getBlockState(missing).isAir() && level.getBlockState(modified).is(Blocks.DIAMOND_BLOCK)
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)+KingdomEconomy.count(server,f.settlement().id(),StockResource.STONE)==wood+stone-1,"One replacement spends one real material and preserves intentional player edits");
        h.assertTrue(!RepairWork.structural(Blocks.BARREL.defaultBlockState()) && !RepairWork.structural(Blocks.SMITHING_TABLE.defaultBlockState()) && !RepairWork.structural(Blocks.RED_BED.defaultBlockState()),"Maintenance cannot manufacture inventories or workstations");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void absentWorkplaceModulePausesWorkerWithoutSpendingOrCrashing(GameTestHelper h) {
        var alias=net.minecraft.resources.ResourceLocation.parse("livingkingdoms:test/missing_"+UUID.randomUUID());var f=fixture(h,956144,alias);fill(f);var w=worker(h,f);var server=h.getLevel().getServer();var jobs=ProfessionSavedData.get(server);
        h.assertTrue(ProfessionService.assignEconomic(f.player(),f.settlement().id(),w.citizen().id(),ProfessionType.LUMBERJACK),"Existing citizen assignment remains valid while module resource is unavailable");
        var p=jobs.profession(w.citizen().id()).orElseThrow();place(w.entity(),jobs.building(p.workplaceId()).orElseThrow().entrance().above());var before=KingdomEconomy.ensure(server,f.settlement().id());
        h.assertTrue(!EconomicWork.tick(w.entity()) && jobs.profession(w.citizen().id()).orElseThrow().workState()==WorkState.BLOCKED
                && KingdomEconomy.ensure(server,f.settlement().id()).equals(before),"Missing NBT safely blocks a job without stock or XP changes");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void changedModuleCannotRepairOutsideSavedFootprintAndRotatedContractsStillMatch(GameTestHelper h) {
        var level=h.getLevel();var alias=net.minecraft.resources.ResourceLocation.parse("livingkingdoms:test/larger_"+UUID.randomUUID());var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);
        var larger=level.getStructureManager().getOrCreate(alias);larger.load(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),catalog.get(BuildingKind.WAREHOUSE).template().save(new CompoundTag()));
        h.assertTrue(BuildingTemplate.validate(BuildingKind.LUMBERJACK_HUT,alias,larger).size().getX()==9,"Fixture is a valid larger native module, not corrupt NBT");
        var f=fixture(h,957168,alias);fill(f);var w=worker(h,f);var server=level.getServer();var jobs=ProfessionSavedData.get(server);
        h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Normal Builder assigned");
        var building=jobs.buildings(f.settlement().id()).stream().filter(b->b.kind()==BuildingKind.LUMBERJACK_HUT).findFirst().orElseThrow();place(w.entity(),building.entrance().above());
        h.assertTrue(BuildingDevelopment.repair(f.player(),f.settlement().id(),building.id()),"Explicit repair order queued");var before=KingdomEconomy.ensure(server,f.settlement().id());var outside=building.origin().offset(8,1,0);var state=level.getBlockState(outside);
        h.assertTrue(!RepairWork.tick(w.entity(),jobs.profession(w.citizen().id()).orElseThrow()) && jobs.profession(w.citizen().id()).orElseThrow().workState()==WorkState.BLOCKED
                && KingdomEconomy.ensure(server,f.settlement().id()).equals(before) && level.getBlockState(outside).equals(state),"Changed dimensions cannot write or spend resources outside the persisted reservation");
        var rectangle=catalog.get(BuildingKind.HOUSE_THIRD);
        for(var rotation:Rotation.values()) {
            var size=rectangle.rotatedSize(rotation);var at=f.center().offset(0,-1,0);var desc=new SettlementLayoutMetadata.Building(rectangle.kind(),rectangle.id(),at,rotation,
                    new PlotBounds(at.getX(),at.getZ(),at.getX()+size.getX()-1,at.getZ()+size.getZ()-1),rectangle.worldPosition(rectangle.entrance(),at,rotation));
            h.assertTrue(WorkSites.resolve(level,FunctionalBuilding.from(f.settlement().id(),level.dimension().location().toString(),desc,0)).isPresent(),"Valid rectangular footprints and entrances resolve for every rotation");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void repairRejectsSameBlockStateChangesFromPlacementListeners(GameTestHelper h) {
        var f=fixture(h,958192);fill(f);var level=h.getLevel();var jobs=ProfessionSavedData.get(level.getServer());var w=worker(h,f);h.assertTrue(ProfessionService.assignBuilder(f.player(),f.settlement().id(),w.citizen().id()),"Builder assigned");
        var b=jobs.buildings(f.settlement().id()).stream().filter(v->v.kind()==BuildingKind.LUMBERJACK_HUT).findFirst().orElseThrow();place(w.entity(),b.entrance().above());var module=WorkSites.template(level,b);
        var original=module.blocks().stream().filter(v->v.pos().getY()==1 && v.state().hasProperty(RotatedPillarBlock.AXIS)).findFirst().orElseThrow();var at=module.worldPosition(original.pos(),b.origin(),b.rotation());level.setBlock(at,Blocks.AIR.defaultBlockState(),18);
        h.assertTrue(BuildingDevelopment.repair(f.player(),f.settlement().id(),b.id()),"Repair queued");var before=KingdomEconomy.ensure(level.getServer(),f.settlement().id());
        Consumer<BlockEvent.EntityPlaceEvent> alter=event->{if(event.getEntity()==w.entity() && event.getPos().equals(at)) {
            var placed=level.getBlockState(at);var axis=placed.getValue(RotatedPillarBlock.AXIS)==net.minecraft.core.Direction.Axis.X?net.minecraft.core.Direction.Axis.Z:net.minecraft.core.Direction.Axis.X;
            level.setBlock(at,placed.setValue(RotatedPillarBlock.AXIS,axis),3);
        }};NeoForge.EVENT_BUS.addListener(alter);
        try {for(int i=0;i<100;i++) {prime(jobs,w.citizen().id(),level);RepairWork.tick(w.entity(),jobs.profession(w.citizen().id()).orElseThrow());if(jobs.profession(w.citizen().id()).orElseThrow().workState()==WorkState.BLOCKED) break;}}
        finally {NeoForge.EVENT_BUS.unregister(alter);}
        h.assertTrue(level.getBlockState(at).isAir() && KingdomEconomy.ensure(level.getServer(),f.settlement().id()).equals(before) && jobs.profession(w.citizen().id()).orElseThrow().experience()==0,"Changing properties of the same block cannot earn XP or consume stock for an altered placement");
        prime(jobs,w.citizen().id(),level);h.assertTrue(RepairWork.tick(w.entity(),jobs.profession(w.citizen().id()).orElseThrow()),"Correct native state repairs normally after listener removal");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=700)
    public static void sharedFundingAndConstructionObjectiveUpdateThroughNormalUiCapabilities(GameTestHelper h) {
        var f=fixture(h,959216);fill(f);var level=h.getLevel();var server=level.getServer();var data=dev.livingkingdoms.quest.persistence.QuestSavedData.get(server);var player=f.player();
        var board=f.center().east(3);level.setBlock(board,dev.livingkingdoms.block.KingdomBlocks.QUEST_BOARD.get().defaultBlockState(),18);
        var q=new dev.livingkingdoms.quest.expansion.domain.QuestInstance(UUID.randomUUID(),dev.livingkingdoms.quest.expansion.domain.QuestTemplate.MAIN_DEVELOPMENT,
                new dev.livingkingdoms.quest.expansion.domain.QuestSource(f.settlement().id(),null,dev.livingkingdoms.quest.expansion.domain.QuestSourceRole.MAYOR),new dev.livingkingdoms.quest.expansion.domain.QuestObjective.Return(),
                new dev.livingkingdoms.progression.domain.LevelValue(1),dev.livingkingdoms.quest.expansion.domain.QuestDifficulty.VERY_EASY,new dev.livingkingdoms.quest.expansion.domain.QuestRewards(0,0),dev.livingkingdoms.quest.domain.QuestState.AVAILABLE,false,server.overworld().getGameTime(),-1);
        h.assertTrue(data.offer(player.getUUID(),q) && data.acceptExpanded(player.getUUID(),q.id()),"Existing development objective accepted before construction");
        h.assertTrue(dev.livingkingdoms.construction.ConstructionService.planHouse(level,f.settlement().id(),player),"Real safe building plan created");var entry=dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(server).projects(f.settlement().id()).getFirst();
        var mayor=(Villager)level.getEntity(data.mayor(f.settlement().id()).orElseThrow());dev.livingkingdoms.ui.VillageUiService.openDialogue(player,mayor);var session=player.snapshots.getLast().getUUID("session");
        h.assertTrue(!dev.livingkingdoms.ui.VillageUiService.handle(player,new dev.livingkingdoms.ui.UiPayloads.Request(session,entry.project().id(),dev.livingkingdoms.ui.UiPayloads.Action.DEPOSIT_STOCK)),"Stock funding requires the construction view");
        dev.livingkingdoms.ui.VillageUiService.handle(player,new dev.livingkingdoms.ui.UiPayloads.Request(session,new UUID(0,0),dev.livingkingdoms.ui.UiPayloads.Action.CONSTRUCTION));
        h.assertTrue(player.snapshots.getLast().getList("projects",10).getCompound(0).getBoolean("stock_available"),"Snapshot enables funding from actual shared materials");
        h.assertTrue(dev.livingkingdoms.ui.VillageUiService.handle(player,new dev.livingkingdoms.ui.UiPayloads.Request(session,entry.project().id(),dev.livingkingdoms.ui.UiPayloads.Action.DEPOSIT_STOCK))
                && !dev.livingkingdoms.ui.VillageUiService.handle(player,new dev.livingkingdoms.ui.UiPayloads.Request(session,entry.project().id(),dev.livingkingdoms.ui.UiPayloads.Action.DEPOSIT_STOCK)),"Normal UI funds the selected project once without moving stock through player inventory");
        place(player,board.east());h.assertTrue(dev.livingkingdoms.ui.VillageUiService.openBoard(player,board),"Local physical Quest Board opened");var boardToken=player.snapshots.getLast().getUUID("session");
        dev.livingkingdoms.ui.QuestJournalService.open(player);var journalToken=player.snapshots.getLast().getUUID("session");h.assertTrue(!dev.livingkingdoms.ui.VillageUiService.handle(player,new dev.livingkingdoms.ui.UiPayloads.Request(boardToken,new UUID(0,0),dev.livingkingdoms.ui.UiPayloads.Action.REFRESH)),"Opening journal invalidates old world UI capability");
        dev.livingkingdoms.ui.VillageUiService.openBoard(player,board);h.assertTrue(!dev.livingkingdoms.ui.QuestJournalService.owns(player,journalToken),"Reopening physical UI dismisses stale journal capability");
        data.observeTransitions(event->dev.livingkingdoms.ui.QuestNotificationService.deliver(player,event));
        try {
            h.assertTrue(dev.livingkingdoms.construction.ConstructionService.resolve(server,entry.project().id(),player,true),"Native debug completion places the paid structure for event integration test");
            h.assertTrue(data.quest(player.getUUID(),q.id()).orElseThrow().objectiveSatisfied() && player.questNotices.size()==1 && player.questNotices.getFirst().notice().kind()==dev.livingkingdoms.quest.domain.QuestNotice.Kind.OBJECTIVE_COMPLETED,"Physical project completion immediately readies the existing objective and emits one personal notice");
            h.assertTrue(player.hudSnapshots.getLast().getCompound("quest").getBoolean("ready") && !player.snapshots.getLast().getBoolean("remote"),"HUD and the active local board refresh without reopening a stale journal");
            h.assertTrue(!dev.livingkingdoms.construction.ConstructionService.resolve(server,entry.project().id(),player,true) && player.questNotices.size()==1,"Completed project cannot emit another notice");
        } finally {data.observeTransitions(event->dev.livingkingdoms.ui.QuestNotificationService.send(server,event));}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=700)
    public static void resourcesViewUsesBoundCapabilitiesAndRechecksSharedBalanceForTwoPlayers(GameTestHelper h) {
        var f=fixture(h,960240);var player=f.player();var level=h.getLevel();var server=level.getServer();
        var mayor=(Villager)level.getEntity(dev.livingkingdoms.quest.persistence.QuestSavedData.get(server).mayor(f.settlement().id()).orElseThrow());
        player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,16));
        h.assertTrue(dev.livingkingdoms.ui.VillageUiService.openDialogue(player,mayor),"Physical mayor dialogue opens");
        var token=player.snapshots.getLast().getUUID("session");var wood=dev.livingkingdoms.ui.StockUiState.target(StockResource.WOOD);
        h.assertTrue(!stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_DEPOSIT),"Forged transfer cannot bypass the resource view");
        h.assertTrue(stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.CITIZENS)
                && stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK),"Habitantes opens the resource view without replacing its physical anchor");
        h.assertTrue(player.snapshots.getLast().getString("screen").equals("stock") && player.snapshots.getLast().getList("stocks",10).size()==5,"Bounded snapshot supplies every supported resource");
        h.assertTrue(!stockAction(player,token,UUID.randomUUID(),dev.livingkingdoms.ui.UiPayloads.Action.STOCK_DEPOSIT),"Arbitrary quest or citizen targets cannot transfer resources");
        h.assertTrue(stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_DEPOSIT)
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==16 && player.getInventory().getItem(0).isEmpty(),"Normal deposit moves real carried items once");
        var second=new UiTestPlayer(level);place(second,player.blockPosition());OnboardingTestSupport.legacyCollaborator(second,f.settlement());
        h.assertTrue(!stockAction(second,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_WITHDRAW),"A second player cannot use the first player's nonce");
        dev.livingkingdoms.ui.VillageUiService.openDialogue(second,mayor);var secondToken=second.snapshots.getLast().getUUID("session");stockAction(second,secondToken,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK);
        h.assertTrue(stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_WITHDRAW)
                && !stockAction(second,secondToken,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_WITHDRAW),"Two current views cannot both spend the last batch");
        h.assertTrue(KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==0
                && dev.livingkingdoms.quest.DeliveryInventory.count(player.getInventory(),dev.livingkingdoms.quest.expansion.domain.ResourceKind.LOGS)==16
                && second.getInventory().isEmpty(),"Withdrawal conserves materials; the second inventory gets no duplicate");
        h.assertTrue(stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.CITIZENS)
                && player.snapshots.getLast().getString("screen").equals("citizens")
                && !stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_WITHDRAW),"Back restores the citizen view and revokes transfer actions");
        stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK);place(player,f.center().east(30));
        h.assertTrue(!stockAction(player,token,wood,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_DEPOSIT)
                && player.snapshots.getLast().getString("screen").equals("closed"),"Moving out of mayor reach closes the capability");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=700)
    public static void resourceSnapshotsExposeFoodReservesAndRejectFullInventoryAndEquipmentDeposits(GameTestHelper h) {
        var f=fixture(h,961264);fill(f);var player=f.player();var server=h.getLevel().getServer();
        var jobs=ProfessionSavedData.get(server);h.assertTrue(jobs.pledgeFood(f.settlement().id(),100)==100,"Quest rations reserved through the normal persisted ledger");
        var mayor=(Villager)h.getLevel().getEntity(dev.livingkingdoms.quest.persistence.QuestSavedData.get(server).mayor(f.settlement().id()).orElseThrow());
        dev.livingkingdoms.ui.VillageUiService.openDialogue(player,mayor);var token=player.snapshots.getLast().getUUID("session");
        stockAction(player,token,new UUID(0,0),dev.livingkingdoms.ui.UiPayloads.Action.STOCK);
        var rows=player.snapshots.getLast().getList("stocks",10);var food=rows.stream().map(net.minecraft.nbt.CompoundTag.class::cast).filter(t->t.getString("resource").equals("FOOD")).findFirst().orElseThrow();
        h.assertTrue(food.getInt("pledged")==100 && food.getInt("reserved")>=100 && food.getInt("withdraw")==16,"Food view separates nutrition, committed reserves and a bounded physical wheat transfer");
        int before=ProfessionService.food(server,f.settlement().id()).stock();var foodTarget=dev.livingkingdoms.ui.StockUiState.target(StockResource.FOOD);
        h.assertTrue(stockAction(player,token,foodTarget,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_WITHDRAW)
                && before-ProfessionService.food(server,f.settlement().id()).stock()==16*dev.livingkingdoms.config.ProfessionConfig.WHEAT_FOOD.get(),"UI withdrawal spends the same nutrition units as the command");
        h.assertTrue(ProfessionService.food(server,f.settlement().id()).pledged()==100,"Direct UI withdrawal cannot release paid quest rations");
        for(int i=0;i<36;i++) player.getInventory().setItem(i,new ItemStack(Items.BEDROCK,64));
        var iron=dev.livingkingdoms.ui.StockUiState.target(StockResource.IRON);int ironBefore=KingdomEconomy.count(server,f.settlement().id(),StockResource.IRON);
        h.assertTrue(!stockAction(player,token,iron,dev.livingkingdoms.ui.UiPayloads.Action.STOCK_WITHDRAW)
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.IRON)==ironBefore,"Full inventory rejection spends no stock");
        rows=player.snapshots.getLast().getList("stocks",10);var ironRow=rows.stream().map(net.minecraft.nbt.CompoundTag.class::cast).filter(t->t.getString("resource").equals("IRON")).findFirst().orElseThrow();
        h.assertTrue(!ironRow.getBoolean("inventory_room"),"Snapshot explains inventory capacity after a rejected transfer");
        player.getInventory().setItem(0,new ItemStack(Items.IRON_CHESTPLATE));
        h.assertTrue(!stockAction(player,token,dev.livingkingdoms.ui.StockUiState.target(StockResource.EQUIPMENT),dev.livingkingdoms.ui.UiPayloads.Action.STOCK_DEPOSIT)
                && player.getInventory().getItem(0).is(Items.IRON_CHESTPLATE),"Carried equipment cannot turn into forge production credit");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=700)
    public static void physicalStorageSharesOneLedgerAndItsIconsNeverBecomeLoot(GameTestHelper h) {
        var f=fixture(h,962288);var player=f.player();var level=h.getLevel();var server=level.getServer();var pos=player.blockPosition().east();
        level.setBlock(pos,dev.livingkingdoms.block.KingdomBlocks.KINGDOM_STORAGE.get().defaultBlockState(),18);
        player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,16));
        var menu=new dev.livingkingdoms.storage.StorageMenu(1,player.getInventory(),pos,f.settlement().id());
        h.assertTrue(menu.stillValid(player) && menu.clickMenuButton(player,StockResource.WOOD.ordinal()),"Physical menu deposits carried materials into the existing ledger");
        var second=new UiTestPlayer(level);place(second,player.blockPosition());OnboardingTestSupport.legacyCollaborator(second,f.settlement());
        var other=new dev.livingkingdoms.storage.StorageMenu(2,second.getInventory(),pos,f.settlement().id());
        menu.clicked(1,0,net.minecraft.world.inventory.ClickType.SWAP,player);
        menu.clicked(1,0,net.minecraft.world.inventory.ClickType.CLONE,player);
        menu.clicked(1,0,net.minecraft.world.inventory.ClickType.THROW,player);
        h.assertTrue(player.getInventory().isEmpty() && menu.getCarried().isEmpty() && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==16,"Swap, creative clone and throw cannot turn display icons into items");
        h.assertTrue(menu.clickMenuButton(player,6) && !other.clickMenuButton(second,6) && player.getInventory().getItem(0).getCount()==16,"Two physical access points cannot duplicate the last batch");
        player.getInventory().setItem(1,new ItemStack(Items.OAK_LOG,2));menu.quickMoveStack(player,33);
        h.assertTrue(player.getInventory().getItem(0).getCount()==16 && player.getInventory().getItem(1).isEmpty()
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==2,"Chest shift-click deposits exactly the chosen stack, leaving other stacks intact");
        menu.clickMenuButton(player,1);int before=KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD);
        level.destroyBlock(pos,true);h.assertTrue(!menu.stillValid(player) && !menu.clickMenuButton(player,6) && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==before,"Destroying storage closes access and preserves the ledger");
        level.setBlock(pos,dev.livingkingdoms.block.KingdomBlocks.KINGDOM_STORAGE.get().defaultBlockState(),18);
        var replacement=new dev.livingkingdoms.storage.StorageMenu(3,player.getInventory(),pos,f.settlement().id());
        h.assertTrue(replacement.value(1,0)==before,"Replacement storage reconnects to the same resources");
        var stranger=new UiTestPlayer(level);place(stranger,player.blockPosition());
        h.assertTrue(!dev.livingkingdoms.storage.KingdomStorage.open(stranger,pos),"Unjoined player cannot open economic storage");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=700)
    public static void managementUiPaysForHouseCapacityAndRejectsReplayedOrForeignRequests(GameTestHelper h) {
        var f=fixture(h,963312);fill(f);var player=f.player();var server=h.getLevel().getServer();var jobs=ProfessionSavedData.get(server);
        var house=jobs.buildings(f.settlement().id()).stream().filter(b->b.kind()==BuildingKind.HOUSE).findFirst().orElseThrow();
        int oldCapacity=CitizenSavedData.get(server).housing(house.id()).orElseThrow().capacity();
        var mayor=(Villager)h.getLevel().getEntity(dev.livingkingdoms.quest.persistence.QuestSavedData.get(server).mayor(f.settlement().id()).orElseThrow());
        dev.livingkingdoms.ui.VillageUiService.openDialogue(player,mayor);var oldToken=player.snapshots.getLast().getUUID("session");
        h.assertTrue(stockAction(player,oldToken,house.id(),dev.livingkingdoms.ui.UiPayloads.Action.MANAGE),"Mayor capability opens real administration");
        var view=dev.livingkingdoms.ui.ManagementService.snapshot(player).orElseThrow();var token=view.getUUID("session");long revision=view.getLong("revision");
        var request=new dev.livingkingdoms.ui.ManagementPayloads.Request(token,revision,house.id(),dev.livingkingdoms.ui.ManagementPayloads.Action.UPGRADE);
        h.assertTrue(dev.livingkingdoms.ui.ManagementService.handle(player,request),"Actual UI upgrade consumes stored materials");
        h.assertTrue(dev.livingkingdoms.economy.BuildingDevelopment.level(server,f.settlement().id(),house.id())==2
                && CitizenSavedData.get(server).housing(house.id()).orElseThrow().capacity()==oldCapacity+1
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==32,"Level has real housing benefit and exactly one resource cost");
        h.assertTrue(!dev.livingkingdoms.ui.ManagementService.handle(player,request)
                && KingdomEconomy.count(server,f.settlement().id(),StockResource.WOOD)==32,"Repeated packet cannot pay/upgrade twice");
        var stranger=new UiTestPlayer(h.getLevel());place(stranger,player.blockPosition());OnboardingTestSupport.legacyCollaborator(stranger,f.settlement());
        h.assertTrue(!dev.livingkingdoms.ui.ManagementService.handle(stranger,request),"Other collaborator cannot reuse the administrator's capability");
        player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG,64));KingdomEconomy.deposit(player,f.settlement().id(),StockResource.WOOD,64);
        view=dev.livingkingdoms.ui.ManagementService.snapshot(player).orElseThrow();
        h.assertTrue(dev.livingkingdoms.ui.ManagementService.handle(player,new dev.livingkingdoms.ui.ManagementPayloads.Request(token,view.getLong("revision"),house.id(),dev.livingkingdoms.ui.ManagementPayloads.Action.UPGRADE)),"Second paid upgrade reaches level three");
        h.assertTrue(CitizenSavedData.get(server).housing(house.id()).orElseThrow().capacity()==oldCapacity+2
                && !dev.livingkingdoms.economy.BuildingDevelopment.upgrade(player,f.settlement().id(),house.id()),"Level three caps new upgrades without deleting legacy data");
        var loaded=dev.livingkingdoms.economy.EconomySavedData.load(dev.livingkingdoms.economy.EconomySavedData.get(server).save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(loaded.account(f.settlement().id()).orElseThrow().buildingLevel(house.id())==3,"Paid building level persists across native reload");
        view=dev.livingkingdoms.ui.ManagementService.snapshot(player).orElseThrow();
        h.assertTrue(dev.livingkingdoms.ui.ManagementService.handle(player,new dev.livingkingdoms.ui.ManagementPayloads.Request(token,view.getLong("revision"),house.id(),dev.livingkingdoms.ui.ManagementPayloads.Action.REPAIR)),"UI creates a real Builder repair order");
        h.assertTrue(KingdomEconomy.ensure(server,f.settlement().id()).repairOrders().contains(house.id()),"Repair is queued even before a Builder becomes available");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=700)
    public static void previewRequiresDirectMayorConstructionAndDoesNotCreateAProject(GameTestHelper h) {
        var f=fixture(h,964336);var player=f.player();var server=h.getLevel().getServer();var target=dev.livingkingdoms.ui.BuildingPreviewTargets.target(BuildingKind.MARKET);
        h.assertTrue(dev.livingkingdoms.ui.VillageUiService.openShortcut(player),"Joined player can open the ordinary kingdom shortcut");var token=player.snapshots.getLast().getUUID("session");
        stockAction(player,token,target,dev.livingkingdoms.ui.UiPayloads.Action.CONSTRUCTION);
        h.assertTrue(!stockAction(player,token,target,dev.livingkingdoms.ui.UiPayloads.Action.PREVIEW),"Remote shortcut cannot create a mayor placement capability");
        var mayor=(Villager)h.getLevel().getEntity(dev.livingkingdoms.quest.persistence.QuestSavedData.get(server).mayor(f.settlement().id()).orElseThrow());
        dev.livingkingdoms.ui.VillageUiService.openDialogue(player,mayor);token=player.snapshots.getLast().getUUID("session");
        h.assertTrue(!stockAction(player,token,target,dev.livingkingdoms.ui.UiPayloads.Action.PREVIEW),"Preview requires the explicit construction view");
        stockAction(player,token,target,dev.livingkingdoms.ui.UiPayloads.Action.CONSTRUCTION);
        h.assertTrue(stockAction(player,token,target,dev.livingkingdoms.ui.UiPayloads.Action.PREVIEW)
                && dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(server).projects(f.settlement().id()).isEmpty(),"Verified local construction preview surveys without reserving a project or spending materials");h.succeed();
    }
    private static boolean stockAction(UiTestPlayer player,UUID token,UUID target,dev.livingkingdoms.ui.UiPayloads.Action action) {
        return dev.livingkingdoms.ui.VillageUiService.handle(player,new dev.livingkingdoms.ui.UiPayloads.Request(token,target,action));
    }
    private static void prime(ProfessionSavedData jobs,UUID id,ServerLevel level) {var p=jobs.profession(id).orElseThrow();jobs.replace(p,p.work(WorkState.IDLE,Math.max(0,level.getServer().overworld().getGameTime()),0,0));}
    private static void place(Entity entity,BlockPos pos) {entity.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);}
    private static Worker worker(GameTestHelper h,Fixture f) {
        var candidate=ImmigrationService.attempt(h.getLevel(),f.settlement(),true).orElseThrow();h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Real immigrant accepted");
        var c=CitizenSavedData.get(h.getLevel().getServer()).citizen(candidate.id()).orElseThrow();ProfessionService.ensure(h.getLevel(),f.settlement());return new Worker(c,(Villager)h.getLevel().getEntity(c.entityId()));
    }
    private static void fill(Fixture f) {int slot=0;for(var resource:List.of(StockResource.FOOD,StockResource.WOOD,StockResource.STONE,StockResource.IRON)) {f.player().getInventory().setItem(slot++,new ItemStack(resource.item(),64));KingdomEconomy.deposit(f.player(),f.settlement().id(),resource,64);}}
    private static SettlementLayoutMetadata.Building description(BuildingTemplate module,BlockPos at) {return new SettlementLayoutMetadata.Building(module.kind(),module.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+module.size().getX()-1,at.getZ()+module.size().getZ()-1),at.offset(module.entrance()));}
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        return fixture(h,coordinate,null);
    }
    private static Fixture fixture(GameTestHelper h,int coordinate,net.minecraft.resources.ResourceLocation hutTemplate) {
        var level=h.getLevel();var center=h.absolutePos(new BlockPos(coordinate,1,coordinate));int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center);level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);}
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);var buildings=new ArrayList<SettlementLayoutMetadata.Building>();
        for(int i=0;i<8;i++) {
            var kind=i<4?BuildingKind.HOUSE:List.of(BuildingKind.LUMBERJACK_HUT,BuildingKind.MINE,BuildingKind.BLACKSMITH,BuildingKind.BARRACKS).get(i-4);
            var module=catalog.get(kind);var at=center.offset(-32+(i%4)*20,-1,i<4?-26:6);module.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);
            var desc=description(module,at);if(kind==BuildingKind.LUMBERJACK_HUT && hutTemplate!=null) desc=new SettlementLayoutMetadata.Building(kind,hutTemplate,at,desc.rotation(),desc.bounds(),desc.entrance());buildings.add(desc);
        }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of()));level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,s).orElseThrow();ProfessionService.ensure(level,s);level.setDayTime(6000);var player=new UiTestPlayer(level);place(player,mayor.blockPosition().east(2));OnboardingTestSupport.legacyCollaborator(player,s);return new Fixture(s,center,player);
    }
}

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

    private static void prime(ProfessionSavedData jobs,UUID id,ServerLevel level) {var p=jobs.profession(id).orElseThrow();jobs.replace(p,p.work(WorkState.IDLE,Math.max(0,level.getServer().overworld().getGameTime()),0,0));}
    private static void place(Entity entity,BlockPos pos) {entity.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);}
    private static Worker worker(GameTestHelper h,Fixture f) {
        var candidate=ImmigrationService.attempt(h.getLevel(),f.settlement(),true).orElseThrow();h.assertTrue(ImmigrationService.accept(f.player(),f.settlement().id(),candidate.id()),"Real immigrant accepted");
        var c=CitizenSavedData.get(h.getLevel().getServer()).citizen(candidate.id()).orElseThrow();ProfessionService.ensure(h.getLevel(),f.settlement());return new Worker(c,(Villager)h.getLevel().getEntity(c.entityId()));
    }
    private static void fill(Fixture f) {int slot=0;for(var resource:List.of(StockResource.FOOD,StockResource.WOOD,StockResource.STONE,StockResource.IRON)) {f.player().getInventory().setItem(slot++,new ItemStack(resource.item(),64));KingdomEconomy.deposit(f.player(),f.settlement().id(),resource,64);}}
    private static SettlementLayoutMetadata.Building description(BuildingTemplate module,BlockPos at) {return new SettlementLayoutMetadata.Building(module.kind(),module.id(),at,Rotation.NONE,new PlotBounds(at.getX(),at.getZ(),at.getX()+module.size().getX()-1,at.getZ()+module.size().getZ()-1),at.offset(module.entrance()));}
    private static Fixture fixture(GameTestHelper h,int coordinate) {
        var level=h.getLevel();var center=h.absolutePos(new BlockPos(coordinate,1,coordinate));int radius=48;
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++) for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++) level.getChunk(x,z);
        var chunk=new ChunkPos(center);level.getChunkSource().addRegionTicket(FIXTURE,chunk,5,chunk);
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {level.setBlock(center.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),18);for(int y=0;y<13;y++) level.setBlock(center.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);}
        var catalog=BuildingCatalog.load(level,ArchitectureStyle.PLAINS);var buildings=new ArrayList<SettlementLayoutMetadata.Building>();
        for(int i=0;i<8;i++) {
            var kind=i<4?BuildingKind.HOUSE:List.of(BuildingKind.LUMBERJACK_HUT,BuildingKind.MINE,BuildingKind.BLACKSMITH,BuildingKind.BARRACKS).get(i-4);
            var module=catalog.get(kind);var at=center.offset(-32+(i%4)*20,-1,i<4?-26:6);module.template().placeInWorld(level,at,at,SettlementTemplate.settings(),level.random,18);buildings.add(description(module,at));
        }
        var s=Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),center.getX(),center.getY(),center.getZ(),radius),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(s,new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.east().below()),List.of()));level.setBlock(center,Blocks.LODESTONE.defaultBlockState(),18);
        var mayor=NpcService.ensureMayor(level,s).orElseThrow();ProfessionService.ensure(level,s);level.setDayTime(6000);var player=new UiTestPlayer(level);place(player,mayor.blockPosition().east(2));OnboardingTestSupport.legacyCollaborator(player,s);return new Fixture(s,center,player);
    }
}

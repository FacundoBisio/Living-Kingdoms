package dev.livingkingdoms.gametest;

import dev.livingkingdoms.citizen.CitizenEvents;
import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementOrigin;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CitizenGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void foundingMigrationPreservesEntitiesNamesHomesAndDeathHistory(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos center = fixture(helper,240000);
        var settlement = settlement(level,center,SettlementOrigin.FOUNDED);
        var mayor = NpcService.ensureMayor(level,settlement).orElseThrow();
        helper.assertTrue(NpcService.spawnInitialResidents(level,settlement,mayor)==2,"Founding creates its existing two real residents");
        UUID a = mayor.getPersistentData().getUUID("livingkingdoms:founding_resident_0");
        UUID b = mayor.getPersistentData().getUUID("livingkingdoms:founding_resident_1");
        var data = CitizenSavedData.get(level.getServer());
        helper.assertTrue(data.population(settlement.id())==0,"Pending NPC transactions do not register identities before initialization");
        CitizenService.ensureInitialized(level,settlement);
        CitizenService.ensureInitialized(level,settlement);
        helper.assertTrue(data.population(settlement.id())==3 && CitizenService.population(level.getServer(),settlement)==3
                && data.citizens(settlement.id()).size()==3
                && data.byEntity(a).isPresent() && data.byEntity(b).isPresent(),"Mayor and receipt residents migrate once without spawning duplicates");
        helper.assertTrue(data.byEntity(mayor.getUUID()).orElseThrow().role()==CitizenRole.MAYOR
                && data.byEntity(a).orElseThrow().role()==CitizenRole.UNASSIGNED,"Mayor identity remains special");
        data.synchronizeHousing(settlement,housingLayout(settlement,2),building -> 2);
        CitizenService.refreshHomes(level,settlement);
        helper.assertTrue(data.summary(settlement.id()).total()==4 && data.summary(settlement.id()).occupied()==3
                && data.citizens(settlement.id()).stream().allMatch(c -> c.homeId()!=null),"Automatic assignments count all actual citizens and preserve capacity");
        Citizen before = data.byEntity(a).orElseThrow();
        var resident = (Villager)level.getEntity(a);
        var reloaded = EntityType.VILLAGER.create(level); reloaded.load(resident.saveWithoutId(new CompoundTag()));
        CitizenService.apply(before,reloaded);
        helper.assertTrue(reloaded.getUUID().equals(a) && reloaded.getCustomName().getString().equals(before.name())
                && reloaded.hasRestriction() && reloaded.getMaxHealth()==20,"Entity NBT keeps identity/name; home restrictions restore without hostile combat scaling");
        resident.discard();
        CitizenService.ensureInitialized(level,settlement);
        helper.assertTrue(data.byEntity(a).orElseThrow().state()==CitizenState.ACTIVE && data.population(settlement.id())==3,
                "An absent or unloaded UUID never becomes dead and is never recreated");
        var dying = (Villager)level.getEntity(b);
        dying.hurt(level.damageSources().genericKill(),1000);
        helper.assertTrue(data.byEntity(b).orElseThrow().state()==CitizenState.DEAD
                && data.byEntity(b).orElseThrow().homeId()==null && data.population(settlement.id())==2
                && CitizenService.population(level.getServer(),settlement)==2
                && data.summary(settlement.id()).free()==2,"Confirmed entity death preserves identity and frees its housing slot once");
        var reopened = CitizenSavedData.load(data.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
        helper.assertTrue(reopened.byEntity(a).orElseThrow().equals(before)
                && reopened.byEntity(b).orElseThrow().state()==CitizenState.DEAD
                && reopened.summary(settlement.id()).equals(data.summary(settlement.id())),"Citizens, persistent names, homes and death history survive saved-data reload");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void convertedAdultPassPreservesVanillaTradersAndDoesNotClaimLaterArrivals(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos center = fixture(helper,241024);
        var settlement = settlement(level,center,SettlementOrigin.CONVERTED);
        var mayor = NpcService.ensureMayor(level,settlement).orElseThrow();
        var trader = villager(level,center.offset(6,0,6));
        trader.setVillagerData(trader.getVillagerData().setProfession(VillagerProfession.FARMER));
        var named = villager(level,center.offset(-6,0,6)); named.setCustomName(Component.literal("Isolde Meadow"));
        var baby = villager(level,center.offset(6,0,-6)); baby.setBaby(true);
        var outside = villager(level,center.offset(30,0,0));
        var reserved = villager(level,center.offset(-6,0,-6)); reserved.getPersistentData().putString("livingkingdoms:npc","malformed-reserved");
        CitizenService.initializeConverted(level,settlement,List.of(trader,named,baby,outside,reserved));
        var data = CitizenSavedData.get(level.getServer());
        helper.assertTrue(data.population(settlement.id())==3 && data.byEntity(mayor.getUUID()).isPresent()
                && data.byEntity(trader.getUUID()).isPresent() && data.byEntity(named.getUUID()).isPresent(),"Only valid adults inside the converted territory register alongside Mayor");
        helper.assertTrue(NpcIdentity.isUnassigned(trader) && trader.getVillagerData().getProfession()==VillagerProfession.FARMER
                && named.getCustomName().getString().equals("Isolde Meadow"),"Citizen association preserves vanilla trader identities/profession and immersive names");
        helper.assertTrue(data.byEntity(baby.getUUID()).isEmpty() && data.byEntity(outside.getUUID()).isEmpty()
                && data.byEntity(reserved.getUUID()).isEmpty() && data.summary(settlement.id()).total()==0,"Children, outside villagers and reserved NPCs are never claimed; vanilla beds add no housing");
        var later = villager(level,center.offset(10,0,0));
        CitizenService.ensureInitialized(level,settlement);
        CitizenEvents.onJoin(new EntityJoinLevelEvent(later,level));
        helper.assertTrue(data.byEntity(later.getUUID()).isEmpty() && data.population(settlement.id())==3,
                "Initialization is one-time and never claims unrelated villagers that later enter the settlement");
        var loaded = EntityType.VILLAGER.create(level); loaded.load(trader.saveWithoutId(new CompoundTag()));
        CitizenEvents.onJoin(new EntityJoinLevelEvent(loaded,level));
        helper.assertTrue(loaded.hasRestriction() && data.population(settlement.id())==3,
                "A converted citizen's UUID rejoin restores restrictions without changing vanilla NPC identity or duplicating population");
        outside.hurt(level.damageSources().genericKill(),1000);
        helper.assertTrue(data.population(settlement.id())==3,"Unrelated villager deaths cannot change settlement population");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void preparedImmigrantUsesLoadedSafeHousingAndNativeReturnTarget(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos center = fixture(helper,242048);
        var settlement = settlement(level,center,SettlementOrigin.FOUNDED);
        CitizenService.ensureInitialized(level,settlement);
        var data = CitizenSavedData.get(level.getServer());
        data.synchronizeHousing(settlement,housingLayout(settlement,1),building -> 2);
        var home = data.houses(settlement.id()).getFirst();
        var entity = CitizenService.prepareImmigrant(level,settlement,home).orElseThrow();
        helper.assertTrue(level.getEntity(entity.getUUID())==null && entity.isPersistenceRequired()
                && NpcIdentity.read(entity).orElseThrow().role()==NpcRole.RESIDENT,"Safe preparation reserves no world entity before the acceptance transaction");
        var citizen = new Citizen(UUID.randomUUID(),entity.getUUID(),settlement.id(),"Mira Ashbrook",new LevelValue(3),
                CitizenRole.UNASSIGNED,home.id(),CitizenState.ACTIVE,level.getGameTime());
        helper.assertTrue(data.register(citizen),"Prepared citizen obtains one valid housing slot");
        CitizenService.apply(citizen,entity);
        helper.assertTrue(level.addFreshEntity(entity) && data.population(settlement.id())==1,"Adding a prepared entity cannot register a duplicate citizen on join");
        helper.assertTrue(entity.getCustomName().getString().equals(citizen.name()) && entity.getMaxHealth()==20
                && entity.getRestrictCenter().equals(home.entrance()),"Civilian names/home bounds reuse vanilla villagers without hostile scaling");
        entity.setPos(home.entrance().getX()+entity.getRestrictRadius()+5,home.entrance().getY(),home.entrance().getZ()+0.5);
        helper.assertTrue(CitizenService.guideHome(entity),"A citizen drifting beyond its home gets a loaded native walk target");
        var target = entity.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElseThrow();
        helper.assertTrue(target.getTarget().currentBlockPosition().equals(home.entrance()),"Home guidance updates vanilla Brain movement rather than teleporting citizens");
        var distant = center.offset(4096,0,4096);
        var unloadedSettlement = settlement(level,distant,SettlementOrigin.FOUNDED);
        data.synchronizeHousing(unloadedSettlement,housingLayout(unloadedSettlement,1),building -> 2);
        var unloadedHome = data.houses(unloadedSettlement.id()).getFirst();
        var unloadedFeet = unloadedHome.entrance();
        helper.assertTrue(!level.getChunkSource().hasChunk(unloadedFeet.getX()>>4,unloadedFeet.getZ()>>4)
                && CitizenService.prepareImmigrant(level,unloadedSettlement,unloadedHome).isEmpty()
                && !level.getChunkSource().hasChunk(unloadedFeet.getX()>>4,unloadedFeet.getZ()>>4),"Unloaded housing safely refuses a spawn without forcing chunks");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void initializedSettlementCanMigrateAnAlreadyTaggedResidentOnChunkJoin(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos center = fixture(helper,243072);
        var settlement = settlement(level,center,SettlementOrigin.FOUNDED);
        CitizenService.ensureInitialized(level,settlement);
        var lateResident = EntityType.VILLAGER.create(level); lateResident.moveTo(center.getX()+0.5,center.getY(),center.getZ()+0.5,0,0);
        NpcIdentity.attach(lateResident,settlement.id(),NpcRole.RESIDENT);
        helper.assertTrue(level.addFreshEntity(lateResident),"Existing tagged resident UUID joins a loaded initialized settlement");
        var data = CitizenSavedData.get(level.getServer());
        Citizen before = data.byEntity(lateResident.getUUID()).orElseThrow();
        CitizenEvents.onJoin(new EntityJoinLevelEvent(lateResident,level));
        helper.assertTrue(data.population(settlement.id())==1 && data.byEntity(lateResident.getUUID()).orElseThrow().equals(before)
                && lateResident.getPersistentData().getUUID(CitizenService.CITIZEN_ID_KEY).equals(before.id()),"Repeated chunk joins reuse the same persisted citizen name and UUID");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void conflictingLoadedNpcTagsNeverGetClaimedOrBlockCitizenInitialization(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos center = fixture(helper,244096);
        var settlement = settlement(level,center,SettlementOrigin.FOUNDED);
        var reserved = villager(level,center);
        reserved.getPersistentData().putString("livingkingdoms:npc","reserved-malformed");
        QuestSavedData.get(level.getServer()).associateMayor(settlement.id(),reserved.getUUID());
        CitizenService.ensureInitialized(level,settlement);
        var data = CitizenSavedData.get(level.getServer());
        helper.assertTrue(data.initialized(settlement.id()) && data.population(settlement.id())==0
                && reserved.getPersistentData().getString("livingkingdoms:npc").equals("reserved-malformed")
                && !reserved.hasCustomName(),"A stale Mayor UUID cannot steal an entity with a reserved NPC identity or block management initialization");
        var citizen = new Citizen(UUID.randomUUID(),reserved.getUUID(),settlement.id(),"Cedric Hollow",new LevelValue(1),
                CitizenRole.UNASSIGNED,null,CitizenState.ACTIVE,level.getGameTime());
        data.register(citizen);
        CitizenEvents.onJoin(new EntityJoinLevelEvent(reserved,level));
        CitizenService.refreshHomes(level,settlement);
        helper.assertTrue(data.byEntity(reserved.getUUID()).orElseThrow().equals(citizen)
                && !reserved.hasCustomName() && !reserved.hasRestriction()
                && !reserved.getPersistentData().contains(CitizenService.CITIZEN_ID_KEY),
                "Saved history survives while conflicting loaded entities remain untouched across joins and management refresh");
        helper.succeed();
    }

    private static Settlement settlement(ServerLevel level,BlockPos center,SettlementOrigin origin) {
        var settlement = Settlement.established(UUID.randomUUID(),new Territory(level.dimension().location().toString(),
                center.getX(),center.getY(),center.getZ(),24),5,origin,UUID.randomUUID());
        SettlementSavedData.get(level.getServer()).add(settlement);
        return settlement;
    }

    private static SettlementLayoutMetadata housingLayout(Settlement settlement,int count) {
        var t = settlement.territory();
        var center = new BlockPos(t.x(),t.y(),t.z());
        var buildings = new java.util.ArrayList<SettlementLayoutMetadata.Building>();
        var core = center.offset(-6,-1,-9);
        buildings.add(new SettlementLayoutMetadata.Building(BuildingKind.CORE,
                ResourceLocation.parse("livingkingdoms:allied/plains/core"),core,Rotation.NONE,
                new PlotBounds(core.getX(),core.getZ(),core.getX()+12,core.getZ()+12),core.offset(6,0,12)));
        for(int index=0;index<count;index++) {
            var origin = center.offset(index==0?9:-13,0,6);
            buildings.add(new SettlementLayoutMetadata.Building(BuildingKind.HOUSE,
                    ResourceLocation.parse("livingkingdoms:allied/plains/house"),origin,Rotation.NONE,
                    new PlotBounds(origin.getX(),origin.getZ(),origin.getX()+3,origin.getZ()+3),origin.south(4)));
        }
        var layout = new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,buildings,List.of(center.south(4)),List.of());
        layout.validate(settlement.territory());
        return layout;
    }

    private static Villager villager(ServerLevel level,BlockPos feet) {
        var villager = EntityType.VILLAGER.create(level); villager.moveTo(feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,0,0);
        villager.setNoAi(true); villager.setPersistenceRequired();
        if(!level.addFreshEntity(villager)) throw new IllegalStateException("Villager fixture refused");
        return villager;
    }

    private static BlockPos fixture(GameTestHelper helper,int coordinate) {
        var level = helper.getLevel(); var center = helper.absolutePos(new BlockPos(coordinate,1,coordinate));
        for(int x=-32;x<=32;x++) for(int z=-32;z<=32;z++) {
            var feet = center.offset(x,0,z); level.getChunkAt(feet);
            level.setBlock(feet.below(),Blocks.STONE_BRICKS.defaultBlockState(),18);
            for(int y=0;y<4;y++) level.setBlock(feet.above(y),Blocks.AIR.defaultBlockState(),18);
        }
        return center;
    }
}

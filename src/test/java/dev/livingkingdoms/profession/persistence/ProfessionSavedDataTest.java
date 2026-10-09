package dev.livingkingdoms.profession.persistence;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.structure.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class ProfessionSavedDataTest {
    @TempDir Path directory;
    private static Settlement settlement() {
        return Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",0,70,0,48),3,SettlementOrigin.FOUNDED,UUID.randomUUID());
    }
    private static SettlementLayoutMetadata.Building farm(int x) {
        var at=new BlockPos(x,70,0);
        return new SettlementLayoutMetadata.Building(BuildingKind.FARM,ResourceLocation.parse("livingkingdoms:allied/plains/farm"),at,Rotation.NONE,
                new PlotBounds(x,0,x+8,8),at.offset(4,0,8));
    }
    private static SettlementLayoutMetadata layout(SettlementLayoutMetadata.Building... buildings) {
        return new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(buildings),List.of(BlockPos.ZERO),List.of());
    }
    private static Citizen person(Settlement s) {
        return new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Mira Ashbrook",new LevelValue(3),CitizenRole.UNASSIGNED,UUID.randomUUID(),CitizenState.ACTIVE,0);
    }
    private static ProfessionSavedData prepared(Settlement s,Citizen... people) {
        var data=new ProfessionSavedData(); data.synchronize(s,layout(farm(0)),2,List.of(people)); data.ensureFood(s.id(),500); return data;
    }
    private static UUID workplace(ProfessionSavedData d,Settlement s) { return d.buildings(s.id()).getFirst().id(); }
    private static ProfessionSavedData reload(ProfessionSavedData data) { return ProfessionSavedData.load(data.save(new CompoundTag(),null),null); }

    @Test void migrationLeavesOldIdentityHomeAndSharedLevelsUntouched() {
        var s=settlement(); var c=person(s); var d=prepared(s,c);
        var p=d.profession(c.id()).orElseThrow(); assertEquals(ProfessionType.UNASSIGNED,p.type()); assertFalse(p.active());
        assertEquals(1,p.level().value()); assertEquals(0,p.experience()); assertEquals(3,c.level().value());
        d.initialize(c); assertEquals(List.of(p),d.professions(s.id()));
        var mayor=new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Alden Clay",new LevelValue(1),CitizenRole.MAYOR,c.homeId(),CitizenState.ACTIVE,0);
        d.initialize(mayor); assertEquals(ProfessionType.MAYOR,d.profession(mayor.id()).orElseThrow().type()); assertFalse(d.assignFarmer(mayor,workplace(d,s),0));
    }
    @Test void assignmentRejectsUnhousedInactiveForeignUnknownAndNonFarmWorkplaces() {
        var s=settlement(); var c=person(s); var d=prepared(s,c); var farm=workplace(d,s);
        assertFalse(d.assignFarmer(c.withHome(null),farm,0));
        var dead=new Citizen(c.id(),c.entityId(),c.settlementId(),c.name(),c.level(),c.role(),null,CitizenState.DEAD,0);
        assertFalse(d.assignFarmer(dead,farm,0)); assertFalse(d.assignFarmer(person(settlement()),farm,0));
        assertFalse(d.assignFarmer(c,UUID.randomUUID(),0)); assertFalse(d.assignFarmer(c,farm,-1));
        var house=farm(12); house=new SettlementLayoutMetadata.Building(BuildingKind.HOUSE,house.template(),house.origin(),house.rotation(),house.bounds(),house.entrance());
        d.synchronize(s,layout(farm(0),house),2,List.of(c)); assertFalse(d.assignFarmer(c,d.buildings(s.id()).getLast().id(),0));
        assertTrue(d.assignFarmer(c,farm,0));
    }
    @Test void sharedCapacitySameCitizenReplayAndLastSlotRaceCannotOverbook() {
        var s=settlement(); var a=person(s); var b=person(s); var c=person(s); var d=prepared(s,a,b,c); var farm=workplace(d,s);
        assertTrue(d.assignFarmer(a,farm,0)); assertFalse(d.assignFarmer(a,farm,1));
        assertTrue(d.assignFarmer(b,farm,0)); assertFalse(d.assignFarmer(c,farm,0)); assertEquals(2,d.workers(farm));
        var loaded=reload(d); assertFalse(loaded.assignFarmer(c,farm,0)); assertEquals(2,loaded.workers(farm));
    }
    @Test void removalReleasesSlotAndPreservesOnlyFarmerCareerProgressAndIdentity() {
        var s=settlement(); var c=person(s); var d=prepared(s,c); var farm=workplace(d,s); d.assignFarmer(c,farm,0);
        var receipt=d.profession(c.id()).orElseThrow().work(WorkState.WORKING,120,20,0); d.replace(d.profession(c.id()).orElseThrow(),receipt);
        assertTrue(d.harvest(receipt,2,20,5,20)); assertTrue(d.removeFarmer(c.id())); assertFalse(d.removeFarmer(c.id()));
        var p=d.profession(c.id()).orElseThrow(); assertEquals(ProfessionType.UNASSIGNED,p.type()); assertNull(p.workplaceId()); assertEquals(2,p.level().value()); assertEquals(20,p.experience());
        assertEquals(0,d.workers(farm)); assertTrue(d.assignFarmer(c,farm,120)); assertEquals(20,d.profession(c.id()).orElseThrow().experience());
    }
    @Test void harvestReceiptAddsFoodAndXpExactlyOnceAcrossSaveReload() {
        var s=settlement(); var c=person(s); var d=prepared(s,c); d.assignFarmer(c,workplace(d,s),0);
        var p=d.profession(c.id()).orElseThrow(); assertTrue(d.harvest(p,2,5,5,20)); assertFalse(d.harvest(p,2,5,5,20));
        var loaded=reload(d); assertFalse(loaded.harvest(p,2,5,5,20)); assertEquals(new FoodStock(2,500,2),loaded.food(s.id(),500)); assertEquals(5,loaded.profession(c.id()).orElseThrow().experience());
        assertFalse(loaded.replace(p,p.work(WorkState.WORKING,120,0,0)));
    }
    @Test void deathAndMissingHomeOrBuildingRetireWithoutRespawningOrDestroyingFarm() {
        var s=settlement(); var a=person(s); var b=person(s); var d=prepared(s,a,b); var farm=workplace(d,s);
        d.assignFarmer(a,farm,0); d.assignFarmer(b,farm,0); assertTrue(d.retire(a.id())); assertFalse(d.retire(a.id())); assertEquals(1,d.workers(farm));
        d.synchronize(s,layout(farm(0)),2,List.of(a,b.withHome(null))); assertEquals(0,d.workers(farm)); assertTrue(d.building(farm).isPresent()); assertEquals(2,d.professions(s.id()).size());
        d.assignFarmer(b,farm,0); d.synchronize(s,null,2,List.of(a,b)); assertFalse(d.profession(b.id()).orElseThrow().active());
        assertTrue(d.profession(a.id()).orElseThrow().workplaceId().equals(farm)); assertEquals(2,reload(d).professions(s.id()).size());
    }
    @Test void capacityReductionDeterministicallyKeepsOneAssignmentAndRegistrationIsIdempotent() {
        var s=settlement(); var a=person(s); var b=person(s); var d=prepared(s,a,b); var farm=workplace(d,s);
        d.assignFarmer(a,farm,0); d.assignFarmer(b,farm,0); d.synchronize(s,layout(farm(0)),1,List.of(a,b));
        assertEquals(1,d.workers(farm)); assertTrue(d.profession(a.id()).orElseThrow().active()); assertFalse(d.profession(b.id()).orElseThrow().active());
        d.synchronize(s,layout(farm(0)),1,List.of(a,b)); assertEquals(1,d.buildings(s.id()).size()); assertEquals(farm,workplace(reload(d),s));
    }
    @Test void foodStorageIsBoundedAndCapacityChangesPreserveLifetimeContribution() {
        var s=settlement(); var d=prepared(s); assertEquals(500,d.addFood(s.id(),600)); assertEquals(0,d.addFood(s.id(),3));
        d.ensureFood(s.id(),100); assertEquals(new FoodStock(100,100,500),d.food(s.id(),100));
        d.ensureFood(s.id(),500); assertEquals(2,d.addFood(s.id(),2)); assertEquals(new FoodStock(102,500,502),reload(d).food(s.id(),500));
        assertThrows(IllegalArgumentException.class,()->d.addFood(s.id(),-1));
    }
    @Test void allProfessionVocabularyWorkStatesAndOptionalTraitsSurviveSerialization() {
        var s=settlement(); var d=prepared(s); var farm=workplace(d,s);
        for(var type:ProfessionType.values()) {
            var c=person(s); d.initialize(c); var old=d.profession(c.id()).orElseThrow();
            d.replace(old,new Profession(c.id(),s.id(),type,new LevelValue(4),123,type==ProfessionType.FARMER?farm:null,false,WorkState.UNLOADED,200,42,3,Set.of(CitizenTrait.BRAVE,CitizenTrait.SOCIABLE)));
        }
        for(var state:WorkState.values()) { var c=person(s); d.initialize(c); var p=d.profession(c.id()).orElseThrow(); d.replace(p,p.work(state,300,43,2)); }
        assertEquals(d.professions(s.id()),reload(d).professions(s.id()));
    }
    @Test void actualSavedDataFileReopensWorkersBuildingsAndFood() {
        SharedConstants.tryDetectVersion(); var s=settlement(); var c=person(s);
        var storage=new DimensionDataStorage(directory.toFile(),null,null); var d=ProfessionSavedData.getOrCreate(storage,directory);
        d.synchronize(s,layout(farm(0)),2,List.of(c)); d.ensureFood(s.id(),500); d.assignFarmer(c,workplace(d,s),0); d.addFood(s.id(),14);
        storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertTrue(Files.isRegularFile(directory.resolve(ProfessionSavedData.DATA_NAME+".dat")));
        var loaded=ProfessionSavedData.getOrCreate(new DimensionDataStorage(directory.toFile(),null,null),directory);
        assertEquals(d.professions(s.id()),loaded.professions(s.id())); assertEquals(d.buildings(s.id()),loaded.buildings(s.id())); assertEquals(d.food(s.id(),500),loaded.food(s.id(),500)); assertFalse(loaded.isDirty());
    }
    @Test void corruptedAndFutureSavedFilesFailClosedWithoutOverwriting() throws Exception {
        SharedConstants.tryDetectVersion(); var tag=new ProfessionSavedData().save(new CompoundTag(),null); tag.putInt("schema_version",99);
        var root=new CompoundTag(); root.put("data",tag); var file=directory.resolve(ProfessionSavedData.DATA_NAME+".dat"); NbtIo.writeCompressed(root,file);
        for(int attempt=0;attempt<2;attempt++) {
            byte[] original=Files.readAllBytes(file); var storage=new DimensionDataStorage(directory.toFile(),null,null);
            assertThrows(IllegalStateException.class,()->ProfessionSavedData.getOrCreate(storage,directory)); storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(original,Files.readAllBytes(file));
            Files.write(file,new byte[]{0x1f,(byte)0x8b,0x08});
        }
    }
    @Test void malformedOverbookedForeignAssignmentsAndDuplicatedDataAreRejected() {
        var s=settlement(); var a=person(s); var b=person(s); var d=prepared(s,a,b); var farm=workplace(d,s); d.assignFarmer(a,farm,0); d.assignFarmer(b,farm,0);
        var original=d.save(new CompoundTag(),null); var corrupt=original.copy(); corrupt.getList("buildings",10).getCompound(0).putInt("slots",1); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(corrupt,null));
        var foreign=original.copy(); foreign.getList("professions",10).getCompound(0).putUUID("settlement",UUID.randomUUID()); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(foreign,null));
        var duplicate=original.copy(); duplicate.getList("professions",10).add(duplicate.getList("professions",10).getCompound(0).copy()); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(duplicate,null));
        var unknown=original.copy(); unknown.getList("professions",10).getCompound(0).putString("type","UNKNOWN"); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(unknown,null));
        var missing=original.copy(); missing.remove("food"); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(missing,null));
    }
    @Test void boundStoresRejectOffThreadReadsAssignmentAndProduction() {
        var s=settlement(); var c=person(s); var d=new ProfessionSavedData(Thread.currentThread()); d.synchronize(s,layout(farm(0)),2,List.of(c)); d.ensureFood(s.id(),500); var farm=workplace(d,s);
        CompletableFuture.runAsync(()-> { assertThrows(IllegalStateException.class,()->d.assignFarmer(c,farm,0)); assertThrows(IllegalStateException.class,()->d.addFood(s.id(),2)); assertThrows(IllegalStateException.class,()->d.profession(c.id())); }).join();
        assertEquals(0,d.workers(farm)); assertEquals(0,d.food(s.id(),500).stock());
    }
    @Test void boundedWorkRegionAndCapabilitiesStayIndependentFromCitizenEntities() {
        var s=settlement(); var f=FunctionalBuilding.from(s.id(),"minecraft:overworld",farm(0),2); var cells=new HashSet<BlockPos>();
        assertTrue(f.capabilities().contains(BuildingCapability.FOOD_PRODUCTION));
        for(int i=0;i<f.cropVolume();i++) { var pos=f.cropPosition(i); assertTrue(f.containsCrop(pos)); assertTrue(cells.add(pos)); }
        assertEquals(243,cells.size()); assertFalse(f.containsCrop(new BlockPos(9,72,0))); assertFalse(f.containsCrop(new BlockPos(0,70,0))); assertFalse(f.containsCrop(new BlockPos(0,74,0)));
        assertEquals(f.cropPosition(0),f.cropPosition(f.cropVolume())); assertThrows(UnsupportedOperationException.class,()->f.capabilities().clear());
    }
    @Test void farmerLevelsHaveMeaningfulThresholdsModestCooldownBenefitAndACap() {
        assertEquals(1,FarmerProgression.level(19,5,20)); assertEquals(2,FarmerProgression.level(20,5,20)); assertEquals(3,FarmerProgression.level(60,5,20));
        assertEquals(5,FarmerProgression.level(200,5,20)); assertEquals(5,FarmerProgression.level(1000000,5,20));
        assertEquals(120,FarmerProgression.cooldown(120,1,5)); assertEquals(96,FarmerProgression.cooldown(120,5,5));
        assertEquals(1,FarmerProgression.food(2,.5)); assertEquals(0,FarmerProgression.food(2,0)); assertThrows(IllegalArgumentException.class,()->FarmerProgression.food(2,Double.NaN));
    }
    @Test void immigrationFoodModifierIsStableAtThresholdsAndInterpolatesGently() {
        assertEquals(.1,FarmerProgression.foodModifier(0,20,80,.1)); assertEquals(.1,FarmerProgression.foodModifier(20,20,80,.1));
        assertEquals(.55,FarmerProgression.foodModifier(50,20,80,.1),.00001); assertEquals(1,FarmerProgression.foodModifier(80,20,80,.1)); assertEquals(1,FarmerProgression.foodModifier(500,20,80,.1));
        assertEquals(0,FarmerProgression.foodModifier(0,20,80,0)); assertThrows(IllegalArgumentException.class,()->FarmerProgression.foodModifier(2,20,20,.1));
    }
}

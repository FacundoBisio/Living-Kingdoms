package dev.livingkingdoms.profession.persistence;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuilderSavedDataTest {
    private final Settlement s=Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",0,70,0,48),1,SettlementOrigin.FOUNDED,UUID.randomUUID());
    private Citizen citizen() { return new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Rowan",new LevelValue(3),CitizenRole.UNASSIGNED,UUID.randomUUID(),CitizenState.ACTIVE,0); }
    private static SettlementLayoutMetadata.Building building(BuildingKind kind,int x) {
        var at=new BlockPos(x,70,0); return new SettlementLayoutMetadata.Building(kind,ResourceLocation.parse("livingkingdoms:allied/plains/"+kind.name().toLowerCase(Locale.ROOT)),
                at,Rotation.NONE,new PlotBounds(x,0,x+8,8),at.offset(4,0,8));
    }
    private SettlementLayoutMetadata layout() {
        return new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(building(BuildingKind.TOWN_HALL,0),building(BuildingKind.FARM,12),building(BuildingKind.BARRACKS,24)),List.of(BlockPos.ZERO),List.of());
    }
    private ProfessionSavedData prepared(Citizen... people) {
        var d=new ProfessionSavedData(); d.synchronize(s,layout(),2,4,2,List.of(people)); d.ensureFood(s.id(),500); return d;
    }
    private UUID workplace(ProfessionSavedData d,BuildingKind kind) { return d.buildings(s.id()).stream().filter(b -> b.kind()==kind).findFirst().orElseThrow().id(); }
    private static ProfessionSavedData reload(ProfessionSavedData d) { return ProfessionSavedData.load(d.save(new CompoundTag(),null),null); }

    @Test void administrativeBuildingProvidesConfiguredExclusiveBuilderSlots() {
        var a=citizen(); var b=citizen(); var c=citizen(); var d=prepared(a,b,c); var hall=workplace(d,BuildingKind.TOWN_HALL);
        assertTrue(d.building(hall).orElseThrow().capabilities().contains(BuildingCapability.BUILDER_WORKPLACE));
        assertTrue(d.assignBuilder(a,hall,0)); assertTrue(d.assignBuilder(b,hall,0)); assertFalse(d.assignBuilder(c,hall,0));
        assertEquals(2,d.workers(hall)); assertTrue(d.removeBuilder(a.id())); assertFalse(d.removeBuilder(a.id())); assertTrue(d.assignBuilder(c,hall,0));
        assertEquals(2,reload(d).workers(hall));
    }
    @Test void assignmentRequiresActiveUnassignedHousedCitizenAndOwnAdministrativeWorkplace() {
        var c=citizen(); var d=prepared(c); var hall=workplace(d,BuildingKind.TOWN_HALL);
        assertFalse(d.assignBuilder(c.withHome(null),hall,0)); assertFalse(d.assignBuilder(c.withState(CitizenState.DEAD),hall,0));
        assertFalse(d.assignBuilder(c,hall,-1)); assertFalse(d.assignBuilder(c,workplace(d,BuildingKind.FARM),0));
        assertFalse(d.assignBuilder(c,UUID.randomUUID(),0));
        var mayor=new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Mayor",new LevelValue(1),CitizenRole.MAYOR,c.homeId(),CitizenState.ACTIVE,0);
        assertFalse(d.assignBuilder(mayor,hall,0));
        var foreign=new Citizen(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Visitor",new LevelValue(1),CitizenRole.UNASSIGNED,c.homeId(),CitizenState.ACTIVE,0);
        assertFalse(d.assignBuilder(foreign,hall,0)); assertTrue(d.assignFarmer(c,workplace(d,BuildingKind.FARM),0)); assertFalse(d.assignBuilder(c,hall,0));
        assertTrue(d.removeFarmer(c.id())); assertTrue(d.assignBuilder(c,hall,0)); assertFalse(d.assignBuilder(c,hall,0));
    }
    @Test void canAssignUsesBuilderCapabilityAndSameSharedCapacity() {
        var c=citizen(); var d=prepared(c); var p=d.profession(c.id()).orElseThrow();
        assertTrue(ProfessionService.canAssign(c,p,d.buildings(s.id()),d,ProfessionType.BUILDER));
        assertFalse(ProfessionService.canAssign(c.withHome(null),p,d.buildings(s.id()),d,ProfessionType.BUILDER));
        d.assignBuilder(c,workplace(d,BuildingKind.TOWN_HALL),0);
        assertFalse(ProfessionService.canAssign(c,d.profession(c.id()).orElseThrow(),d.buildings(s.id()),d,ProfessionType.BUILDER));
    }
    @Test void builderCareerHomeTraitsAndWorkStatesPersistIndependently() {
        var c=citizen(); var d=prepared(c); var hall=workplace(d,BuildingKind.TOWN_HALL); d.assignBuilder(c,hall,0);
        d.builderExperience(d.profession(c.id()).orElseThrow(),120,5,40);
        for(var state:List.of(WorkState.IDLE,WorkState.WORKING,WorkState.BLOCKED,WorkState.UNLOADED,WorkState.SLEEPING)) {
            var p=d.profession(c.id()).orElseThrow(); d.replace(p,p.work(state,400,0,3)); var loaded=reload(d).profession(c.id()).orElseThrow();
            assertEquals(state,loaded.workState()); assertEquals(hall,loaded.workplaceId()); assertEquals(120,loaded.experience()); assertEquals(3,loaded.level().value());
        }
        assertNotNull(c.homeId()); assertEquals(3,c.level().value());
    }
    @Test void threeSeparateCareersSurviveRemovalSwitchesAndReload() {
        var c=citizen(); var d=prepared(c); var hall=workplace(d,BuildingKind.TOWN_HALL);
        d.assignFarmer(c,workplace(d,BuildingKind.FARM),0); d.harvest(d.profession(c.id()).orElseThrow(),3,60,5,20); d.removeFarmer(c.id());
        d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); d.guardExperience(d.profession(c.id()).orElseThrow(),80,5,40); d.removeGuard(c.id());
        assertTrue(d.assignBuilder(c,hall,0)); assertEquals(0,d.profession(c.id()).orElseThrow().experience());
        d.builderExperience(d.profession(c.id()).orElseThrow(),120,5,40); d.removeBuilder(c.id()); d=reload(d);
        d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); assertEquals(80,d.profession(c.id()).orElseThrow().experience()); d.removeGuard(c.id());
        d.assignFarmer(c,workplace(d,BuildingKind.FARM),0); assertEquals(60,d.profession(c.id()).orElseThrow().experience()); d.removeFarmer(c.id());
        d.assignBuilder(c,hall,0); assertEquals(120,d.profession(c.id()).orElseThrow().experience()); assertEquals(3,d.food(s.id(),500).stock());
    }
    @Test void xpCompareAndSetRejectsStaleUnassignedAndInvalidRewards() {
        var c=citizen(); var d=prepared(c); assertFalse(d.builderExperience(d.profession(c.id()).orElseThrow(),20,5,40));
        d.assignBuilder(c,workplace(d,BuildingKind.TOWN_HALL),0); var p=d.profession(c.id()).orElseThrow();
        assertFalse(d.builderExperience(p,0,5,40)); assertTrue(d.builderExperience(p,40,5,40)); assertFalse(d.builderExperience(p,40,5,40));
        assertEquals(40,d.profession(c.id()).orElseThrow().experience()); assertEquals(2,d.profession(c.id()).orElseThrow().level().value());
    }
    @Test void deathRetiresWorkplaceWithoutDestroyingCareerOrAdministrativeBuilding() {
        var c=citizen(); var d=prepared(c); var hall=workplace(d,BuildingKind.TOWN_HALL); d.assignBuilder(c,hall,0);
        d.builderExperience(d.profession(c.id()).orElseThrow(),40,5,40); assertTrue(d.retire(c.id())); assertFalse(d.retire(c.id()));
        var loaded=reload(d); assertEquals(0,loaded.workers(hall)); assertEquals(40,loaded.profession(c.id()).orElseThrow().experience()); assertTrue(loaded.building(hall).isPresent());
    }
    @Test void shrinkingCapacityAndRemovingWorkplaceRetiresOnlyInvalidBuilders() {
        var a=citizen(); var b=citizen(); var d=prepared(a,b); var hall=workplace(d,BuildingKind.TOWN_HALL); d.assignBuilder(a,hall,0); d.assignBuilder(b,hall,0);
        d.synchronize(s,layout(),2,4,1,List.of(a,b)); assertTrue(d.profession(a.id()).orElseThrow().active()); assertFalse(d.profession(b.id()).orElseThrow().active());
        d.synchronize(s,null,2,4,2,List.of(a,b)); assertEquals(0,d.workers(hall)); assertFalse(d.profession(a.id()).orElseThrow().active());
    }
    @Test void convertedPlazaIsStableMetadataAndDoesNotRequireAnInventedBuildingLayout() {
        var converted=Settlement.established(UUID.randomUUID(),s.territory(),1,SettlementOrigin.CONVERTED,UUID.randomUUID()); var d=new ProfessionSavedData();
        d.synchronize(converted,null,2,4,2,List.of()); var plaza=d.buildings(converted.id()).getFirst(); assertEquals(BuildingKind.TOWN_HALL,plaza.kind());
        assertEquals(new PlotBounds(0,0,0,0),plaza.bounds()); assertEquals(2,plaza.workplaceSlots());
        d=reload(d); d.synchronize(converted,null,2,4,2,List.of()); assertEquals(plaza,d.buildings(converted.id()).getFirst());
        d.synchronize(converted,layout(),2,4,2,List.of()); assertEquals(1,d.buildings(converted.id()).stream().filter(b -> b.supports(ProfessionType.BUILDER)).count());
    }
    @Test void oldSchemasPreserveFarmerGuardFoodAndRevisions() {
        var c=citizen(); var d=prepared(c); d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); d.guardExperience(d.profession(c.id()).orElseThrow(),80,5,40);
        d.addFood(s.id(),23); var tag=d.save(new CompoundTag(),null); tag.putInt("schema_version",2); var loaded=ProfessionSavedData.load(tag,null);
        assertEquals(d.profession(c.id()),loaded.profession(c.id())); assertEquals(d.food(s.id(),500),loaded.food(s.id(),500)); assertEquals(d.employmentRevision(c.id()),loaded.employmentRevision(c.id()));
        tag.putInt("schema_version",1); tag.remove("careers"); loaded=ProfessionSavedData.load(tag,null); assertEquals(d.profession(c.id()),loaded.profession(c.id()));
    }
    @Test void generationChangesOnlyForEmploymentAndRemainsReloadSafe() {
        var c=citizen(); var d=prepared(c); var hall=workplace(d,BuildingKind.TOWN_HALL); d.assignBuilder(c,hall,0); long revision=d.employmentRevision(c.id());
        d.builderExperience(d.profession(c.id()).orElseThrow(),40,5,40); var p=d.profession(c.id()).orElseThrow(); d.replace(p,p.work(WorkState.WORKING,100,0,0));
        assertEquals(revision,reload(d).employmentRevision(c.id())); d.removeBuilder(c.id()); d.assignBuilder(c,hall,0); assertEquals(revision+2,reload(d).employmentRevision(c.id()));
    }
    @Test void futureSchemaAndInvalidBuilderWorkplaceFailClosed() {
        var c=citizen(); var d=prepared(c); d.assignBuilder(c,workplace(d,BuildingKind.TOWN_HALL),0); var tag=d.save(new CompoundTag(),null);
        tag.getList("professions",10).getCompound(0).putUUID("workplace",workplace(d,BuildingKind.FARM)); assertThrows(IllegalArgumentException.class,() -> ProfessionSavedData.load(tag,null));
        var future=d.save(new CompoundTag(),null); future.putInt("schema_version",4); assertThrows(IllegalArgumentException.class,() -> ProfessionSavedData.load(future,null));
        assertThrows(IllegalArgumentException.class,() -> new Profession(c.id(),s.id(),ProfessionType.BUILDER,new LevelValue(1),0,null,true,WorkState.IDLE,0,0,0,Set.of()));
    }
    @Test void levelBonusIsModestCappedAndConfigurable() {
        assertEquals(1,BuilderPolicy.level(39,5,40)); assertEquals(2,BuilderPolicy.level(40,5,40)); assertEquals(5,BuilderPolicy.level(1000000,5,40));
        assertEquals(1,BuilderPolicy.speedMultiplier(1,5,.25)); assertEquals(1.125,BuilderPolicy.speedMultiplier(3,5,.25));
        assertEquals(1.25,BuilderPolicy.speedMultiplier(100,5,.25)); assertEquals(1,BuilderPolicy.speedMultiplier(1,1,.25)); assertEquals(1,BuilderPolicy.speedMultiplier(5,5,0));
        assertThrows(IllegalArgumentException.class,() -> BuilderPolicy.speedMultiplier(1,5,.5)); assertThrows(IllegalArgumentException.class,() -> BuilderPolicy.speedMultiplier(0,5,.25));
    }
    @Test void builderMutationsRejectAnotherThread() {
        var c=citizen(); var d=new ProfessionSavedData(Thread.currentThread()); d.synchronize(s,layout(),2,4,2,List.of(c)); var hall=workplace(d,BuildingKind.TOWN_HALL);
        var failure=java.util.concurrent.CompletableFuture.runAsync(() -> d.assignBuilder(c,hall,0)); assertThrows(java.util.concurrent.CompletionException.class,failure::join); assertEquals(0,d.workers(hall));
    }
}

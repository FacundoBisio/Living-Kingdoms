package dev.livingkingdoms.profession.persistence;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.faction.*;
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

class GuardSavedDataTest {
    private final Settlement s=Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",0,70,0,48),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
    private Citizen citizen() { return new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Mira",new LevelValue(3),CitizenRole.UNASSIGNED,UUID.randomUUID(),CitizenState.ACTIVE,0); }
    private static SettlementLayoutMetadata.Building building(BuildingKind kind,int x) {
        var at=new BlockPos(x,70,0); return new SettlementLayoutMetadata.Building(kind,ResourceLocation.parse("livingkingdoms:allied/plains/"+kind.name().toLowerCase(Locale.ROOT)),at,Rotation.NONE,new PlotBounds(x,0,x+8,8),at.offset(4,0,8));
    }
    private SettlementLayoutMetadata layout() { return new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(building(BuildingKind.BARRACKS,0),building(BuildingKind.FARM,12),building(BuildingKind.WATCHTOWER,24)),List.of(BlockPos.ZERO),List.of()); }
    private ProfessionSavedData data(Citizen... people) { var d=new ProfessionSavedData(); d.synchronize(s,layout(),2,4,List.of(people)); d.ensureFood(s.id(),500); return d; }
    private UUID workplace(ProfessionSavedData d,BuildingKind kind) { return d.buildings(s.id()).stream().filter(b -> b.kind()==kind).findFirst().orElseThrow().id(); }
    private static ProfessionSavedData reload(ProfessionSavedData d) { return ProfessionSavedData.load(d.save(new CompoundTag(),null),null); }

    @Test void guardWorkplaceRequiresBarracksHomeActiveCitizenAndMatchingSettlement() {
        var c=citizen(); var d=data(c); var b=workplace(d,BuildingKind.BARRACKS);
        assertFalse(d.assignGuard(c.withHome(null),b,0)); assertFalse(d.assignGuard(c,workplace(d,BuildingKind.FARM),0));
        assertFalse(d.assignGuard(c,UUID.randomUUID(),0)); assertFalse(d.assignGuard(c,b,-1));
        var foreign=new Citizen(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Foreign",new LevelValue(1),CitizenRole.UNASSIGNED,c.homeId(),CitizenState.ACTIVE,0);
        assertFalse(d.assignGuard(foreign,b,0)); assertTrue(d.assignGuard(c,b,0)); assertFalse(d.assignGuard(c,b,0));
    }
    @Test void sharedCapacityRejectsOverbookingAndRemovalReleasesOnlyOneSlot() {
        var people=new ArrayList<Citizen>(); for(int i=0;i<5;i++) people.add(citizen()); var d=data(people.toArray(Citizen[]::new)); var b=workplace(d,BuildingKind.BARRACKS);
        for(int i=0;i<4;i++) assertTrue(d.assignGuard(people.get(i),b,0)); assertFalse(d.assignGuard(people.get(4),b,0));
        assertTrue(d.removeGuard(people.getFirst().id())); assertFalse(d.removeGuard(people.getFirst().id())); assertEquals(3,d.workers(b));
        assertTrue(d.assignGuard(people.get(4),b,0)); assertEquals(4,reload(d).workers(b));
    }
    @Test void guardProgressIsSeparateFromFarmerProgressAcrossJobSwitchAndReload() {
        var c=citizen(); var d=data(c); var farm=workplace(d,BuildingKind.FARM); var barracks=workplace(d,BuildingKind.BARRACKS);
        assertTrue(d.assignFarmer(c,farm,0)); assertTrue(d.harvest(d.profession(c.id()).orElseThrow(),2,40,5,20));
        assertTrue(d.removeFarmer(c.id())); assertTrue(d.assignGuard(c,barracks,0)); assertEquals(0,d.profession(c.id()).orElseThrow().experience());
        assertTrue(d.guardExperience(d.profession(c.id()).orElseThrow(),80,5,40)); assertTrue(d.removeGuard(c.id())); d=reload(d);
        assertTrue(d.assignFarmer(c,farm,0)); assertEquals(40,d.profession(c.id()).orElseThrow().experience()); assertTrue(d.removeFarmer(c.id()));
        assertTrue(d.assignGuard(c,barracks,0)); assertEquals(80,d.profession(c.id()).orElseThrow().experience()); assertEquals(3,c.level().value()); assertEquals(2,d.food(s.id(),500).stock());
    }
    @Test void staleXpReceiptAndUnassignedXpAreRejected() {
        var c=citizen(); var d=data(c); assertFalse(d.guardExperience(d.profession(c.id()).orElseThrow(),20,5,40));
        d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); var p=d.profession(c.id()).orElseThrow();
        assertFalse(d.guardExperience(p,0,5,40)); assertTrue(d.guardExperience(p,40,5,40)); assertFalse(d.guardExperience(p,40,5,40)); assertEquals(2,d.profession(c.id()).orElseThrow().level().value());
    }
    @Test void guardDeathRetiresWorkplaceAndSecurityWithoutRespawn() {
        var c=citizen(); var d=data(c); var b=workplace(d,BuildingKind.BARRACKS); d.assignGuard(c,b,0);
        assertEquals(26,GuardPolicy.security(d.professions(s.id()),d.buildings(s.id()))); assertTrue(d.retire(c.id())); assertFalse(d.retire(c.id()));
        assertEquals(0,d.workers(b)); assertEquals(14,GuardPolicy.security(d.professions(s.id()),d.buildings(s.id()))); assertFalse(reload(d).profession(c.id()).orElseThrow().active());
    }
    @Test void capacityShrinkAndMissingBarracksRetireGuardsDeterministically() {
        var a=citizen(); var b=citizen(); var d=data(a,b); var id=workplace(d,BuildingKind.BARRACKS); d.assignGuard(a,id,0); d.assignGuard(b,id,0);
        d.synchronize(s,layout(),2,1,List.of(a,b)); assertTrue(d.profession(a.id()).orElseThrow().active()); assertFalse(d.profession(b.id()).orElseThrow().active());
        d.synchronize(s,null,2,4,List.of(a,b)); assertEquals(0,d.workers(id));
    }
    @Test void everyGuardStatePersistsAndOwnsTheSameHomeAndWorkplace() {
        var c=citizen(); var d=data(c); var b=workplace(d,BuildingKind.BARRACKS); d.assignGuard(c,b,0);
        for(var state:List.of(WorkState.IDLE,WorkState.PATROLLING,WorkState.ENGAGING,WorkState.RETURNING,WorkState.SLEEPING)) {
            var p=d.profession(c.id()).orElseThrow(); d.replace(p,p.work(state,100,0,0)); var loaded=reload(d).profession(c.id()).orElseThrow(); assertEquals(state,loaded.workState()); assertEquals(b,loaded.workplaceId());
        }
    }
    @Test void schemaOneFarmerMigrationPreservesAllFieldsAndRetiredHistory() {
        var c=citizen(); var d=data(c); d.assignFarmer(c,workplace(d,BuildingKind.FARM),0); d.harvest(d.profession(c.id()).orElseThrow(),3,60,5,20); d.removeFarmer(c.id());
        var tag=d.save(new CompoundTag(),null); tag.putInt("schema_version",1); tag.remove("careers");
        var loaded=ProfessionSavedData.load(tag,null); assertEquals(d.professions(s.id()),loaded.professions(s.id())); assertEquals(d.food(s.id(),500),loaded.food(s.id(),500));
        loaded.assignGuard(c,workplace(loaded,BuildingKind.BARRACKS),0); loaded.removeGuard(c.id()); loaded.assignFarmer(c,workplace(loaded,BuildingKind.FARM),0); assertEquals(60,loaded.profession(c.id()).orElseThrow().experience());
    }
    @Test void malformedAndFutureCareerRecordsFailClosed() {
        var c=citizen(); var d=data(c); d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); var tag=d.save(new CompoundTag(),null);
        tag.getList("careers",10).getCompound(0).putLong("xp",-1); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(tag,null));
        var future=d.save(new CompoundTag(),null); future.putInt("schema_version",3); assertThrows(IllegalArgumentException.class,()->ProfessionSavedData.load(future,null));
    }
    @Test void hostilityUsesCentralRelationsAndAvoidsAlliedOrNeutralPairs() {
        assertTrue(GuardPolicy.hostile(Faction.PILLAGER)); assertTrue(GuardPolicy.hostile(Faction.BANDIT)); assertTrue(GuardPolicy.hostile(Faction.UNDEAD)); assertFalse(GuardPolicy.hostile(Faction.ALLIED_KINGDOM));
        assertEquals(FactionRelation.NEUTRAL,FactionRelations.between(Faction.PILLAGER,Faction.BANDIT));
    }
    @Test void defenseAndChaseRangesUseHorizontalBoundaries() {
        assertTrue(GuardPolicy.within(32,0,0,0,32)); assertFalse(GuardPolicy.within(33,0,0,0,32)); assertTrue(GuardPolicy.within(39,0,0,0,40)); assertFalse(GuardPolicy.within(41,0,0,0,40));
    }
    @Test void equipmentThresholdsAndXpAreModerateAndCapped() {
        assertEquals(1,GuardPolicy.equipmentTier(1,3)); assertEquals(2,GuardPolicy.equipmentTier(3,3)); assertEquals(1,GuardPolicy.level(39,5,40)); assertEquals(2,GuardPolicy.level(40,5,40)); assertEquals(5,GuardPolicy.level(100000,5,40));
    }
    @Test void securityImmigrationIsNeverZeroAndScoreIsBounded() {
        assertEquals(.85,GuardPolicy.immigrationModifier(0,.85)); assertEquals(1,GuardPolicy.immigrationModifier(60,.85));
        var c=citizen(); var d=data(c); d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); var p=d.profession(c.id()).orElseThrow(); assertEquals(100,GuardPolicy.security(Collections.nCopies(20,p),d.buildings(s.id())));
    }
    @Test void mayorAndDeadCitizensCannotTakeGuardSlots() {
        var c=citizen(); var d=data(c); var b=workplace(d,BuildingKind.BARRACKS);
        var mayor=new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Mayor",new LevelValue(1),CitizenRole.MAYOR,c.homeId(),CitizenState.ACTIVE,0);
        assertFalse(d.assignGuard(mayor,b,0)); var dead=new Citizen(c.id(),c.entityId(),s.id(),c.name(),c.level(),c.role(),null,CitizenState.DEAD,0); assertFalse(d.assignGuard(dead,b,0));
    }
    @Test void firstGuardCareerCannotLeakXpIntoFirstFarmerAssignment() {
        var c=citizen(); var d=data(c); d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); d.guardExperience(d.profession(c.id()).orElseThrow(),200,5,40); d.removeGuard(c.id());
        d=reload(d); assertTrue(d.assignFarmer(c,workplace(d,BuildingKind.FARM),0)); assertEquals(0,d.profession(c.id()).orElseThrow().experience()); assertEquals(1,d.profession(c.id()).orElseThrow().level().value());
    }
    @Test void employmentGenerationChangesOnlyWhenAssignmentChangesAndSurvivesReload() {
        var c=citizen(); var d=data(c); var id=workplace(d,BuildingKind.BARRACKS); assertEquals(0,d.employmentRevision(c.id()));
        d.assignGuard(c,id,0); long generation=d.employmentRevision(c.id()); var p=d.profession(c.id()).orElseThrow(); d.guardExperience(p,40,5,40);
        p=d.profession(c.id()).orElseThrow(); d.replace(p,p.work(WorkState.ENGAGING,100,0,0)); assertEquals(generation,d.employmentRevision(c.id()));
        d.removeGuard(c.id()); d.assignGuard(c,id,0); assertEquals(generation+2,reload(d).employmentRevision(c.id()));
    }
    @Test void guardMutationsRejectAnotherThread() {
        var c=citizen(); var d=new ProfessionSavedData(Thread.currentThread()); d.synchronize(s,layout(),2,4,List.of(c)); var id=workplace(d,BuildingKind.BARRACKS);
        var failure=java.util.concurrent.CompletableFuture.runAsync(() -> d.assignGuard(c,id,0)); assertThrows(java.util.concurrent.CompletionException.class,failure::join); assertEquals(0,d.workers(id));
    }
    @Test void physicalGuardSavedDataFileReopensCareerAndCapacity(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        net.minecraft.SharedConstants.tryDetectVersion(); var c=citizen(); var storage=new net.minecraft.world.level.storage.DimensionDataStorage(dir.toFile(),null,null);
        var d=ProfessionSavedData.getOrCreate(storage,dir); d.synchronize(s,layout(),2,4,List.of(c)); d.assignGuard(c,workplace(d,BuildingKind.BARRACKS),0); d.guardExperience(d.profession(c.id()).orElseThrow(),200,5,40);
        storage.save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var loaded=ProfessionSavedData.getOrCreate(new net.minecraft.world.level.storage.DimensionDataStorage(dir.toFile(),null,null),dir);
        assertEquals(d.profession(c.id()),loaded.profession(c.id())); assertEquals(1,loaded.workers(workplace(loaded,BuildingKind.BARRACKS))); assertEquals(d.employmentRevision(c.id()),loaded.employmentRevision(c.id()));
    }
}

package dev.livingkingdoms.citizen.persistence;

import dev.livingkingdoms.citizen.CitizenNames;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.structure.ArchitectureStyle;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.PlotBounds;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class CitizenSavedDataTest {
    @TempDir Path directory;
    private static Settlement settlement() {
        return new Settlement(UUID.randomUUID(), "Oakhaven", Faction.ALLIED_KINGDOM, 1, 99,
                new Territory("minecraft:overworld", 0, 70, 0, 48));
    }
    private static SettlementLayoutMetadata.Building building(BuildingKind kind, int x) {
        return new SettlementLayoutMetadata.Building(kind, ResourceLocation.parse("livingkingdoms:allied/plains/" + kind.name().toLowerCase()),
                new BlockPos(x,70,0), Rotation.NONE, new PlotBounds(x,0,x+8,8), new BlockPos(x+4,70,8));
    }
    private static SettlementLayoutMetadata layout(SettlementLayoutMetadata.Building... buildings) {
        return new SettlementLayoutMetadata(ArchitectureStyle.PLAINS, List.of(buildings), List.of(BlockPos.ZERO), List.of());
    }
    private static Citizen person(UUID settlement, int number) {
        UUID entity = UUID.randomUUID();
        return new Citizen(UUID.randomUUID(), entity, settlement, CitizenNames.forIdentity(entity), new LevelValue(1),
                CitizenRole.UNASSIGNED, null, CitizenState.ACTIVE, number);
    }
    private static ImmigrationCandidate candidate(UUID settlement, long created) {
        return new ImmigrationCandidate(UUID.randomUUID(), settlement, "Elara Riverstone", new LevelValue(3),
                CitizenRole.FARMER, created, created + 100);
    }
    private static CitizenSavedData reload(CitizenSavedData original) {
        return CitizenSavedData.load(original.save(new CompoundTag(), null), null);
    }

    @Test void housesHaveStableIdentitiesAndVariantCapacitiesWhileSheltersAreExcluded() {
        var settlement = settlement(); var data = new CitizenSavedData();
        var one = building(BuildingKind.HOUSE, 0); var two = building(BuildingKind.HOUSE_VARIANT, 12);
        var camp = building(BuildingKind.FOUNDING_CAMP, -12);
        data.synchronizeHousing(settlement, layout(one,two,camp), b -> b.kind() == BuildingKind.HOUSE_VARIANT ? 4 : 2);
        assertEquals(new HousingSummary(6,0,6), data.summary(settlement.id()));
        UUID firstId = Housing.identity(settlement.id(), one);
        assertTrue(data.housing(firstId).isPresent());
        data.synchronizeHousing(settlement, layout(two,camp,one), b -> b.kind() == BuildingKind.HOUSE_VARIANT ? 4 : 2);
        assertTrue(data.housing(firstId).isPresent());
        assertEquals(2, data.houses(settlement.id()).size());
        data.synchronizeHousing(settlement, layout(one,two,camp), b -> 2, true);
        assertEquals(3, data.houses(settlement.id()).size());
        assertNotEquals(firstId, Housing.identity(settlement().id(), one));
    }

    @Test void autoAssignmentNeverOverbooksAndPopulationComesFromActiveIdentities() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement, layout(building(BuildingKind.HOUSE,0)), b -> 2);
        for (int i=0;i<3;i++) assertTrue(data.register(person(settlement.id(),i)));
        data.assignHomes(settlement.id());
        assertEquals(3, data.population(settlement.id()));
        assertEquals(new HousingSummary(2,2,0), data.summary(settlement.id()));
        assertEquals(1, data.citizens(settlement.id()).stream().filter(c -> c.homeId() == null).count());
        data.assignHomes(settlement.id());
        assertEquals(2, data.assignedResidents(data.houses(settlement.id()).getFirst().id()).size());
        assertThrows(UnsupportedOperationException.class, () -> data.citizens(settlement.id()).clear());
    }

    @Test void capacityReductionAndHouseRemovalReconcileHomesWithoutLosingCitizens() {
        var settlement = settlement(); var data = new CitizenSavedData(); var house = building(BuildingKind.HOUSE,0);
        data.synchronizeHousing(settlement, layout(house), b -> 4);
        for (int i=0;i<4;i++) data.register(person(settlement.id(),i));
        data.assignHomes(settlement.id());
        data.synchronizeHousing(settlement, layout(house), b -> 2);
        assertEquals(new HousingSummary(2,2,0), data.summary(settlement.id()));
        assertEquals(4, data.population(settlement.id()));
        data.synchronizeHousing(settlement, null, b -> 2);
        assertEquals(new HousingSummary(0,0,0), data.summary(settlement.id()));
        assertTrue(data.citizens(settlement.id()).stream().allMatch(c -> c.homeId() == null));
    }

    @Test void duplicateEntityAndCitizenRegistrationCannotStealIdentity() {
        var settlement = settlement(); var data = new CitizenSavedData(); var citizen = person(settlement.id(),0);
        assertTrue(data.register(citizen)); assertFalse(data.register(citizen));
        assertFalse(data.register(new Citizen(UUID.randomUUID(),citizen.entityId(),settlement().id(),"Alden Clay",new LevelValue(1),
                CitizenRole.UNASSIGNED,null,CitizenState.ACTIVE,1)));
        assertFalse(data.register(new Citizen(citizen.id(),UUID.randomUUID(),settlement.id(),"Alden Clay",new LevelValue(1),
                CitizenRole.UNASSIGNED,null,CitizenState.ACTIVE,1)));
        assertEquals(citizen,data.byEntity(citizen.entityId()).orElseThrow());
        assertEquals(1,data.population(settlement.id()));
    }

    @Test void unknownWrongSettlementAndFullHomesAreRejected() {
        var settlement = settlement(); var other = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 1);
        UUID home = data.houses(settlement.id()).getFirst().id();
        var citizen = person(settlement.id(),0);
        assertThrows(IllegalArgumentException.class, () -> data.register(citizen.withHome(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> data.register(person(other.id(),0).withHome(home)));
        assertTrue(data.register(citizen.withHome(home)));
        assertThrows(IllegalArgumentException.class, () -> data.register(person(settlement.id(),1).withHome(home)));
        assertEquals(new HousingSummary(1,1,0),data.summary(settlement.id()));
    }

    @Test void deathFreesHousingAndKeepsHistoryWhileUnloadingChangesNothing() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        var citizen = person(settlement.id(),0); data.register(citizen); data.assignHomes(settlement.id());
        Citizen assigned = data.byEntity(citizen.entityId()).orElseThrow();
        var loaded = reload(data);
        assertEquals(assigned,loaded.byEntity(citizen.entityId()).orElseThrow());
        assertEquals(1,loaded.population(settlement.id()));
        assertFalse(loaded.markDead(UUID.randomUUID()));
        assertTrue(loaded.markDead(citizen.entityId())); assertFalse(loaded.markDead(citizen.entityId()));
        assertEquals(0,loaded.population(settlement.id()));
        assertEquals(new HousingSummary(2,0,2),loaded.summary(settlement.id()));
        assertEquals(CitizenState.DEAD,loaded.byEntity(citizen.entityId()).orElseThrow().state());
        assertNull(loaded.byEntity(citizen.entityId()).orElseThrow().homeId());
        assertEquals(assigned.name(),reload(loaded).byEntity(citizen.entityId()).orElseThrow().name());
    }

    @Test void oldResidentsReceiveNewHousePlacesBeforeImmigrants() {
        var settlement = settlement(); var data = new CitizenSavedData();
        for (int i=0;i<3;i++) data.register(person(settlement.id(),i));
        var request = candidate(settlement.id(),1); data.addCandidate(request);
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        assertTrue(data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),2).isEmpty());
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0),building(BuildingKind.HOUSE_VARIANT,12)),b -> 2);
        assertEquals(1,data.summary(settlement.id()).free());
        assertTrue(data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),2).isPresent());
        assertEquals(4,data.population(settlement.id()));
    }

    @Test void candidateAcceptanceConsumesExactlyOneRequestAndReservesOneHome() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        var request = candidate(settlement.id(),1); data.addCandidate(request);
        UUID entity = UUID.randomUUID();
        var accepted = data.acceptCandidate(settlement.id(),request.id(),entity,2).orElseThrow();
        assertEquals(request.id(),accepted.id()); assertEquals(entity,accepted.entityId()); assertEquals(request.name(),accepted.name());
        assertEquals(request.level(),accepted.level()); assertEquals(CitizenRole.UNASSIGNED,accepted.role());
        assertTrue(data.candidates(settlement.id()).isEmpty());
        assertTrue(data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),2).isEmpty());
        assertEquals(new HousingSummary(2,1,1),data.summary(settlement.id()));
        var loaded = reload(data);
        assertEquals(accepted,loaded.citizen(accepted.id()).orElseThrow());
        assertTrue(loaded.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),3).isEmpty());
    }

    @Test void twoPlayersCannotAcceptSameCandidateOrOverbookLastPlaceWithDifferentCandidates() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 1);
        var one = candidate(settlement.id(),1); var two = candidate(settlement.id(),1);
        data.addCandidate(one); data.addCandidate(two);
        assertTrue(data.acceptCandidate(settlement.id(),one.id(),UUID.randomUUID(),2).isPresent());
        assertTrue(data.acceptCandidate(settlement.id(),one.id(),UUID.randomUUID(),2).isEmpty());
        assertTrue(data.acceptCandidate(settlement.id(),two.id(),UUID.randomUUID(),2).isEmpty());
        assertEquals(1,data.population(settlement.id())); assertEquals(new HousingSummary(1,1,0),data.summary(settlement.id()));
        assertEquals(List.of(two),data.candidates(settlement.id()));
    }

    @Test void explicitHomeAcceptanceHonorsSelectedHomeAndRejectsAnotherSettlementOrFullHouse() {
        var settlement = settlement(); var other = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0),building(BuildingKind.HOUSE_VARIANT,12)),b -> 1);
        data.synchronizeHousing(other,layout(building(BuildingKind.HOUSE,0)),b -> 1);
        var request = candidate(settlement.id(),1); data.addCandidate(request);
        UUID foreignHome = data.houses(other.id()).getFirst().id();
        assertTrue(data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),foreignHome,2).isEmpty());
        UUID chosen = data.houses(settlement.id()).getLast().id();
        assertEquals(chosen,data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),chosen,2).orElseThrow().homeId());
        var second = candidate(settlement.id(),1); data.addCandidate(second);
        assertTrue(data.acceptCandidate(settlement.id(),second.id(),UUID.randomUUID(),chosen,2).isEmpty());
        assertEquals(1,data.summary(settlement.id()).free());
        assertTrue(data.acceptCandidate(other.id(),second.id(),UUID.randomUUID(),2).isEmpty());
    }

    @Test void disabledHousingCannotAcceptEvenIfItHasNominalCapacity() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        var saved = data.save(new CompoundTag(),null);
        saved.getList("housing",10).getCompound(0).putString("status","DISABLED");
        var loaded = CitizenSavedData.load(saved,null); var request = candidate(settlement.id(),1); loaded.addCandidate(request);
        assertEquals(new HousingSummary(0,0,0),loaded.summary(settlement.id()));
        assertTrue(loaded.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),loaded.houses(settlement.id()).getFirst().id(),2).isEmpty());
    }

    @Test void failedEntitySpawnCanRestoreCandidateAndReleaseReservationExactlyOnce() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 1);
        var request = candidate(settlement.id(),1); data.addCandidate(request);
        var accepted = data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),2).orElseThrow();
        assertTrue(data.rollbackAcceptance(accepted,request)); assertFalse(data.rollbackAcceptance(accepted,request));
        assertEquals(0,data.population(settlement.id())); assertEquals(new HousingSummary(1,0,1),data.summary(settlement.id()));
        assertEquals(request,data.candidate(request.id()).orElseThrow());
        assertTrue(data.byEntity(accepted.entityId()).isEmpty());
        assertTrue(data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),3).isPresent());
    }

    @Test void staleDeadAcceptanceCannotBeRolledBackToRespawnIdentity() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 1);
        var request = candidate(settlement.id(),1); data.addCandidate(request);
        var accepted = data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),2).orElseThrow();
        data.markDead(accepted.entityId());
        assertFalse(data.rollbackAcceptance(accepted,request));
        assertEquals(CitizenState.DEAD,data.citizen(accepted.id()).orElseThrow().state());
        assertTrue(data.candidates(settlement.id()).isEmpty());
    }

    @Test void declineAndExpirationAreIdempotentAndExpiredAcceptanceNeverCreatesCitizen() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        var one = candidate(settlement.id(),1); var two = candidate(settlement.id(),20);
        data.addCandidate(one); data.addCandidate(two);
        assertEquals(0,data.expireCandidates(settlement.id(),100));
        assertTrue(data.acceptCandidate(settlement.id(),one.id(),UUID.randomUUID(),101).isEmpty());
        assertEquals(1,data.expireCandidates(settlement.id(),101)); assertEquals(0,data.expireCandidates(settlement.id(),101));
        assertEquals(two,data.removeCandidate(two.id()).orElseThrow()); assertTrue(data.removeCandidate(two.id()).isEmpty());
        assertEquals(0,data.population(settlement.id())); assertEquals(2,data.summary(settlement.id()).free());
    }

    @Test void namesRolesLevelsStatesCandidatesAndCooldownSurviveActualSaveFileReopen() {
        SharedConstants.tryDetectVersion();
        var storage = new DimensionDataStorage(directory.toFile(),null,null);
        var data = CitizenSavedData.getOrCreate(storage,directory); var settlement = settlement();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        for (CitizenRole role : CitizenRole.values()) {
            UUID entity = UUID.randomUUID();
            data.register(new Citizen(UUID.randomUUID(),entity,settlement.id(),CitizenNames.forIdentity(entity),new LevelValue(3),role,
                    null,role == CitizenRole.GUARD ? CitizenState.MISSING : CitizenState.ACTIVE,20));
        }
        data.assignHomes(settlement.id());
        data.markDead(data.citizens(settlement.id()).getFirst().entityId());
        var request = candidate(settlement.id(),30); data.addCandidate(request); data.schedule(settlement.id(),72000); data.markInitialized(settlement.id());
        storage.save(); IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(Files.isRegularFile(directory.resolve(CitizenSavedData.DATA_NAME + ".dat")));
        var loaded = CitizenSavedData.getOrCreate(new DimensionDataStorage(directory.toFile(),null,null),directory);
        assertEquals(data.citizens(settlement.id()),loaded.citizens(settlement.id()));
        assertEquals(data.houses(settlement.id()),loaded.houses(settlement.id()));
        assertEquals(data.summary(settlement.id()),loaded.summary(settlement.id()));
        assertEquals(List.of(request),loaded.candidates(settlement.id()));
        assertEquals(72000,loaded.nextCheck(settlement.id())); assertTrue(loaded.initialized(settlement.id())); assertFalse(loaded.isDirty());
    }

    @Test void futureSchemaAndTruncatedFilesAreNeverOverwritten() throws Exception {
        SharedConstants.tryDetectVersion();
        var tag = new CitizenSavedData().save(new CompoundTag(),null); tag.putInt("schema_version",99);
        var root = new CompoundTag(); root.put("data",tag);
        Path file = directory.resolve(CitizenSavedData.DATA_NAME + ".dat"); NbtIo.writeCompressed(root,file);
        assertUnloadedFilePreserved(file);
        Files.write(file,new byte[]{0x1f,(byte)0x8b,0x08}); assertUnloadedFilePreserved(file);
    }

    private void assertUnloadedFilePreserved(Path file) throws Exception {
        byte[] original = Files.readAllBytes(file);
        var storage = new DimensionDataStorage(directory.toFile(),null,null);
        assertThrows(IllegalStateException.class, () -> CitizenSavedData.getOrCreate(storage,directory));
        assertThrows(IllegalStateException.class, () -> CitizenSavedData.getOrCreate(storage,directory));
        storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(original,Files.readAllBytes(file));
    }

    @Test void malformedDuplicateIdentitiesAndOverbookedPersistedHomesFailClosed() {
        var settlement = settlement(); var data = new CitizenSavedData();
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        data.register(person(settlement.id(),0)); data.register(person(settlement.id(),1)); data.assignHomes(settlement.id());
        var saved = data.save(new CompoundTag(),null);
        saved.getList("citizens",10).getCompound(1).putUUID("entity",data.citizens(settlement.id()).getFirst().entityId());
        assertThrows(IllegalArgumentException.class, () -> CitizenSavedData.load(saved,null));
        var overbooked = data.save(new CompoundTag(),null); overbooked.getList("housing",10).getCompound(0).putInt("capacity",1);
        assertThrows(IllegalArgumentException.class, () -> CitizenSavedData.load(overbooked,null));
        var wrongHome = data.save(new CompoundTag(),null); wrongHome.getList("citizens",10).getCompound(0).putUUID("home",UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> CitizenSavedData.load(wrongHome,null));
        var unknownRole = data.save(new CompoundTag(),null); unknownRole.getList("citizens",10).getCompound(0).putString("role","FUTURE_UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> CitizenSavedData.load(unknownRole,null));
    }

    @Test void namePoolHasUsefulVariationAndMigrationNamesAreDeterministic() {
        Set<String> names = new HashSet<>();
        for (int i=0;i<100;i++) {
            UUID identity = new UUID(0,i);
            String name = CitizenNames.forIdentity(identity); names.add(name);
            assertEquals(name,CitizenNames.forIdentity(identity)); assertTrue(name.matches("[A-Za-z]+ [A-Za-z]+"));
        }
        assertTrue(names.size() > 30);
    }

    @Test void inactiveCitizensCannotOccupyHomesAndCandidateTimesAreValidated() {
        var settlement = settlement(); var citizen = person(settlement.id(),0);
        assertThrows(IllegalArgumentException.class, () -> new Citizen(citizen.id(),citizen.entityId(),settlement.id(),citizen.name(),
                citizen.level(),citizen.role(),UUID.randomUUID(),CitizenState.DEAD,0));
        assertThrows(IllegalArgumentException.class, () -> new ImmigrationCandidate(UUID.randomUUID(),settlement.id(),"Traveler",
                new LevelValue(1),CitizenRole.UNASSIGNED,1,1));
        assertThrows(IllegalArgumentException.class, () -> new HousingSummary(2,3,-1));
    }

    @Test void emptyStoreSupportsLegacyWorldLazyInitializationWithoutOtherSchemas() {
        var data = reload(new CitizenSavedData()); UUID settlement = UUID.randomUUID();
        assertEquals(0,data.population(settlement)); assertEquals(new HousingSummary(0,0,0),data.summary(settlement));
        assertFalse(data.initialized(settlement)); assertEquals(0,data.nextCheck(settlement)); assertTrue(data.candidates(settlement).isEmpty());
        data.markInitialized(settlement); data.markInitialized(settlement);
        assertTrue(reload(data).initialized(settlement));
    }

    @Test void boundStoresRejectOffThreadAcceptanceMutationAndReads() {
        var settlement = settlement(); var data = new CitizenSavedData(Thread.currentThread());
        data.synchronizeHousing(settlement,layout(building(BuildingKind.HOUSE,0)),b -> 2);
        var request = candidate(settlement.id(),1); data.addCandidate(request);
        CompletableFuture.runAsync(() -> {
            assertThrows(IllegalStateException.class, () -> data.acceptCandidate(settlement.id(),request.id(),UUID.randomUUID(),2));
            assertThrows(IllegalStateException.class, () -> data.register(person(settlement.id(),0)));
            assertThrows(IllegalStateException.class, () -> data.markDead(UUID.randomUUID()));
            assertThrows(IllegalStateException.class, () -> data.summary(settlement.id()));
            assertThrows(IllegalStateException.class, () -> data.schedule(settlement.id(),100));
        }).join();
        assertEquals(0,data.population(settlement.id())); assertEquals(List.of(request),data.candidates(settlement.id()));
    }
}

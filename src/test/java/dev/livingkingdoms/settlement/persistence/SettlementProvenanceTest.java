package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementProvenanceTest {
    private static Settlement create(SettlementProvenance provenance) {
        return new Settlement(UUID.randomUUID(),"Willowrest",Faction.ALLIED_KINGDOM,1,2,
                new Territory("minecraft:overworld",0,70,0,48),provenance);
    }
    @Test void everyOriginAndOptionalKingdomRoundTripsWithoutChangingIdentity() {
        for (var origin : SettlementOrigin.values()) {
            var settlement = create(new SettlementProvenance(origin,Optional.of(UUID.randomUUID()),1700000000123L,Optional.of(UUID.randomUUID())));
            var data = new SettlementSavedData(); data.add(settlement);
            var loaded = SettlementSavedData.load(data.save(new CompoundTag(),null),null);
            assertEquals(settlement,loaded.get(settlement.id()).orElseThrow());
            assertEquals(settlement,loaded.nearestAllied("minecraft:overworld",0,0,0).orElseThrow());
            assertFalse(loaded.isDirty());
        }
    }
    @Test void oldRecordsMigrateWithUnknownTimeAndFounderAndAreWrittenAsSchemaTwo() {
        var old = create(SettlementProvenance.legacy());
        var data = new SettlementSavedData(); data.add(old);
        var saved = data.save(new CompoundTag(),null);
        saved.putInt("schema_version",1);
        saved.getList("settlements",10).getCompound(0).remove("provenance");
        var loaded = SettlementSavedData.load(saved,null);
        assertEquals(old,loaded.get(old.id()).orElseThrow());
        assertEquals(SettlementOrigin.GENERATED,loaded.get(old.id()).orElseThrow().provenance().origin());
        assertTrue(loaded.isDirty());
        assertEquals(3,loaded.save(new CompoundTag(),null).getInt("schema_version"));
    }
    @Test void schemaTwoRejectsUnknownOriginMalformedUuidAndMissingProvenance() {
        var data = new SettlementSavedData(); data.add(create(SettlementProvenance.legacy()));
        var saved = data.save(new CompoundTag(),null);
        var entry = saved.getList("settlements",10).getCompound(0);
        entry.getCompound("provenance").putString("origin","FUTURE");
        assertThrows(IllegalArgumentException.class,()->SettlementSavedData.load(saved,null));
        entry.getCompound("provenance").putString("origin","GENERATED");
        entry.getCompound("provenance").putString("founder","not-a-uuid");
        assertThrows(IllegalArgumentException.class,()->SettlementSavedData.load(saved,null));
        entry.remove("provenance");
        assertThrows(IllegalArgumentException.class,()->SettlementSavedData.load(saved,null));
    }
    @Test void playerOriginsRequireFounderAndKnownCreationTime() {
        assertThrows(IllegalArgumentException.class,()->new SettlementProvenance(SettlementOrigin.FOUNDED,Optional.empty(),1,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->new SettlementProvenance(SettlementOrigin.CONVERTED,Optional.of(UUID.randomUUID()),0,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->new SettlementProvenance(SettlementOrigin.GENERATED,Optional.empty(),-1,Optional.empty()));
        var founded = Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",0,70,0,48),5,SettlementOrigin.FOUNDED,UUID.randomUUID());
        assertTrue(founded.provenance().createdAtEpochMillis()>0);
        assertTrue(founded.provenance().kingdom().isEmpty());
    }
    @Test void failedEstablishmentRemovesTerritoryLayoutAndDerivedEncounterIndex() {
        var data = new SettlementSavedData(); var settlement = create(SettlementProvenance.legacy()); data.add(settlement);
        assertTrue(data.nearAlliedTerritory("minecraft:overworld",0,0,0));
        data.rollbackEstablishment(settlement);
        assertTrue(data.get(settlement.id()).isEmpty());
        assertTrue(data.nearestAllied("minecraft:overworld",0,0,100).isEmpty());
        assertFalse(data.nearAlliedTerritory("minecraft:overworld",0,0,0));
        assertFalse(data.overlaps(settlement.territory()));
        assertThrows(IllegalStateException.class,()->data.rollbackEstablishment(settlement));
        data.add(settlement);
        assertEquals(1,data.settlements().size());
    }
}

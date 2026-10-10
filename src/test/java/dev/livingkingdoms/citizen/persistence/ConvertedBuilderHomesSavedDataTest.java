package dev.livingkingdoms.citizen.persistence;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConvertedBuilderHomesSavedDataTest {
    private final Settlement s=Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",0,70,0,48),1,SettlementOrigin.CONVERTED,UUID.randomUUID());
    private Citizen citizen() { return new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Rowan",new LevelValue(2),CitizenRole.UNASSIGNED,null,CitizenState.ACTIVE,0); }
    private Housing bed() { var head=new BlockPos(6,70,6); return new Housing(Housing.convertedBedIdentity(s.id(),head),s.id(),s.territory().dimension(),Housing.CONVERTED_BED_TEMPLATE,head,head.east(),1,HousingStatus.ACTIVE); }
    @Test void explicitBedHomeAssignsOneRegisteredCitizenWithoutInventedHousing() {
        var d=new CitizenSavedData(); var c=citizen(); d.register(c); assertEquals(0,d.summary(s.id()).total());
        assertTrue(d.assignConvertedBedHome(s,c,bed())); assertEquals(bed().id(),d.citizen(c.id()).orElseThrow().homeId()); assertEquals(1,d.summary(s.id()).total());
        assertEquals(1,d.summary(s.id()).occupied()); assertEquals(0,d.summary(s.id()).free()); assertFalse(d.assignConvertedBedHome(s,c,bed()));
    }
    @Test void sameBedCannotBeStolenAndGenericHomeAssignmentCannotClaimIt() {
        var d=new CitizenSavedData(); var a=citizen(); var b=citizen(); d.register(a); d.register(b);
        assertTrue(d.assignConvertedBedHome(s,a,bed())); assertFalse(d.assignConvertedBedHome(s,b,bed())); d.assignHomes(s.id());
        assertNull(d.citizen(b.id()).orElseThrow().homeId()); assertEquals(1,d.occupancy(bed().id()));
    }
    @Test void convertedLayoutSynchronizationAndReloadPreserveExplicitBed() {
        var d=new CitizenSavedData(); var c=citizen(); d.register(c); d.assignConvertedBedHome(s,c,bed());
        var empty=new SettlementLayoutMetadata(ArchitectureStyle.PLAINS,List.of(),List.of(BlockPos.ZERO),List.of()); d.synchronizeHousing(s,empty,b -> 2);
        var loaded=CitizenSavedData.load(d.save(new CompoundTag(),null),null); loaded.synchronizeHousing(s,empty,b -> 2);
        assertEquals(d.houses(s.id()),loaded.houses(s.id())); assertEquals(d.citizen(c.id()),loaded.citizen(c.id())); assertEquals(1,loaded.summary(s.id()).occupied());
    }
    @Test void brokenOrDeadBedOwnerDisablesCapacityAndOnlyExplicitRevalidationReactivatesIt() {
        var d=new CitizenSavedData(); var c=citizen(); d.register(c); d.assignConvertedBedHome(s,c,bed());
        assertTrue(d.disableConvertedBedHome(bed().id())); assertFalse(d.disableConvertedBedHome(bed().id())); assertNull(d.citizen(c.id()).orElseThrow().homeId()); assertEquals(0,d.summary(s.id()).total());
        assertTrue(d.assignConvertedBedHome(s,d.citizen(c.id()).orElseThrow(),bed())); assertTrue(d.markDead(c.entityId()));
        assertEquals(HousingStatus.DISABLED,d.housing(bed().id()).orElseThrow().status()); assertEquals(0,d.summary(s.id()).free());
    }
    @Test void nativeBedMetadataRejectsWrongIdentityCapacityOriginAndMayor() {
        var d=new CitizenSavedData(); var c=citizen(); d.register(c);
        var founded=Settlement.established(s.id(),s.territory(),1,SettlementOrigin.FOUNDED,UUID.randomUUID()); assertFalse(d.assignConvertedBedHome(founded,c,bed()));
        var mayor=new Citizen(UUID.randomUUID(),UUID.randomUUID(),s.id(),"Mayor",new LevelValue(1),CitizenRole.MAYOR,null,CitizenState.ACTIVE,0); d.register(mayor); assertFalse(d.assignConvertedBedHome(s,mayor,bed()));
        assertThrows(IllegalArgumentException.class,() -> new Housing(UUID.randomUUID(),s.id(),s.territory().dimension(),Housing.CONVERTED_BED_TEMPLATE,bed().position(),bed().entrance(),1,HousingStatus.ACTIVE));
        assertThrows(IllegalArgumentException.class,() -> new Housing(bed().id(),s.id(),s.territory().dimension(),Housing.CONVERTED_BED_TEMPLATE,bed().position(),bed().entrance(),2,HousingStatus.ACTIVE));
    }
}

package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.settlement.domain.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementLifecycleTest {
    private static Settlement founded() { return Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",20,71,20,48),5,SettlementOrigin.FOUNDED,UUID.randomUUID()); }
    @Test void explicitLifecycleRoundTripsWithoutLosingHistoryOrIndexes() {
        for(var lifecycle:SettlementLifecycle.values()) {
            var p=founded().withLifecycle(lifecycle); var data=new SettlementSavedData(); data.add(p);
            var loaded=SettlementSavedData.load(data.save(new CompoundTag(),null),null);
            assertEquals(p,loaded.get(p.id()).orElseThrow()); assertEquals(p,loaded.nearestAllied("minecraft:overworld",20,20,0).orElseThrow());
        }
    }
    @Test void schemaOneAndNinePointZeroFoundationsMigrateToEstablished() {
        for(int schema: new int[]{1,2}) {
            var p=founded(); var data=new SettlementSavedData(); data.add(p); var tag=data.save(new CompoundTag(),null);
            tag.putInt("schema_version",schema); var record=tag.getList("settlements",10).getCompound(0); record.remove("lifecycle");
            if(schema==1) record.remove("provenance");
            var loaded=SettlementSavedData.load(tag,null); assertEquals(SettlementLifecycle.ESTABLISHED,loaded.get(p.id()).orElseThrow().lifecycle());
            assertTrue(loaded.isDirty()); if(schema==2) assertEquals(p.provenance(),loaded.get(p.id()).orElseThrow().provenance());
        }
    }
    @Test void stateReplacementIsConditionalAndPreservesSettlementIdentity() {
        var p=founded().withLifecycle(SettlementLifecycle.FOUNDING); var data=new SettlementSavedData(); data.add(p);
        assertTrue(data.replace(p,p.withLifecycle(SettlementLifecycle.ESTABLISHED))); assertFalse(data.replace(p,p));
        assertEquals(p.provenance(),data.get(p.id()).orElseThrow().provenance());
    }
    @Test void newSchemaDoesNotSilentlyDefaultMalformedStates() {
        var data=new SettlementSavedData(); data.add(founded()); var tag=data.save(new CompoundTag(),null);
        var record=tag.getList("settlements",10).getCompound(0); record.remove("lifecycle");
        assertThrows(IllegalArgumentException.class,()->SettlementSavedData.load(tag,null)); record.putString("lifecycle","FUTURE");
        assertThrows(IllegalArgumentException.class,()->SettlementSavedData.load(tag,null));
    }
}

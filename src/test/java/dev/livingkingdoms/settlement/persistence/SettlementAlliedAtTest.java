package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementAlliedAtTest {
    @Test void indexedContainingLookupUsesCircleFactionDimensionAndExactBoundary() {
        var data=new SettlementSavedData();
        var allied=settlement(Faction.ALLIED_KINGDOM,"minecraft:overworld",-256,-257,48);
        var hostile=settlement(Faction.PILLAGER,"minecraft:overworld",1024,0,48);
        data.add(allied); data.add(hostile);
        assertEquals(allied,data.alliedAt("minecraft:overworld",-208,-257).orElseThrow());
        assertTrue(data.alliedAt("minecraft:overworld",-207,-257).isEmpty());
        assertTrue(data.alliedAt("minecraft:overworld",-208,-209).isEmpty());
        assertTrue(data.alliedAt("minecraft:the_nether",-256,-257).isEmpty());
        assertTrue(data.alliedAt("minecraft:overworld",1024,0).isEmpty());
    }
    @Test void importedLargeTerritoriesRemainVisibleFarFromIndexedCenterAfterReload() {
        var data=new SettlementSavedData(); var allied=settlement(Faction.ALLIED_KINGDOM,"minecraft:overworld",0,0,8192);
        data.add(allied);
        assertEquals(allied,data.alliedAt("minecraft:overworld",8192,0).orElseThrow());
        var reloaded=SettlementSavedData.load(data.save(new CompoundTag(),null),null);
        assertEquals(allied,reloaded.alliedAt("minecraft:overworld",-8192,0).orElseThrow());
        assertTrue(reloaded.alliedAt("minecraft:overworld",8193,0).isEmpty());
    }
    private static Settlement settlement(Faction faction,String dimension,int x,int z,int radius) {
        return new Settlement(UUID.randomUUID(),"Test",faction,1,0,new Territory(dimension,x,64,z,radius));
    }
}

package dev.livingkingdoms.storage;

import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.config.ProfessionConfig;
import net.minecraft.server.MinecraftServer;
import java.util.UUID;

/** Project supplies were already debited. Display their escrow without subtracting it from stock twice. */
public final class StorageAccounting {
    private StorageAccounting() {}
    public record ConstructionTotals(int reserved,int consumed) {}
    public static ConstructionTotals construction(MinecraftServer server,UUID kingdom,StockResource resource) {
        if(resource.material()==null) return new ConstructionTotals(0,0);
        int reserved=0,consumed=0;
        for(var entry:ConstructionSavedData.get(server).projects(kingdom)) {
            int units=resource.unitsForItems(entry.project().supplied().getOrDefault(resource.material(),0),ProfessionConfig.WHEAT_FOOD.get());
            if(entry.project().state()==ConstructionState.COMPLETED) consumed+=units;else reserved+=units;
        }
        return new ConstructionTotals(reserved,consumed);
    }
    public static int low(int value) {return value&65535;}
    public static int high(int value) {return value>>>16;}
    public static int join(int low,int high) {return (low&65535)|((high&65535)<<16);}
}

package dev.livingkingdoms.construction;

import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

public final class ConstructionEvents {
    private ConstructionEvents() {}
    public static void onTick(ServerTickEvent.Post event) {
        var server=event.getServer(); long now=ConstructionService.now(server);
        if(now%20!=0) return;
        var storage=ConstructionSavedData.get(server);
        for(var id:storage.ready(now,2)) ConstructionService.resolve(server,id,null);
    }
    public static void onChunkLoad(ChunkEvent.Load event) {
        if(event.getLevel() instanceof ServerLevel level) {
            var pos=event.getChunk().getPos(); String dimension=level.dimension().location().toString();
            level.getServer().execute(() -> ConstructionSavedData.get(level.getServer()).chunkLoaded(dimension,pos));
        }
    }
}

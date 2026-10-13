package dev.livingkingdoms.profession;

import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import dev.livingkingdoms.structure.BuildingTemplate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import java.util.*;

/** Bounded native templates, invalidated by resource-manager identity. Path navigation uses getChunkNow in 1.21.1. */
public final class WorkSites {
    private record Cache(Object resources,Map<ResourceLocation,BuildingTemplate> templates) {}
    private static final Map<MinecraftServer,Cache> CACHES=new WeakHashMap<>();
    private WorkSites() {}
    public static BuildingTemplate template(ServerLevel level,FunctionalBuilding building) {
        var resources=level.getServer().getResourceManager();var cache=CACHES.get(level.getServer());
        if(cache==null || cache.resources()!=resources) {cache=new Cache(resources,new LinkedHashMap<>());CACHES.put(level.getServer(),cache);}
        var known=cache.templates().get(building.template());if(known!=null) return known;
        var module=BuildingTemplate.validate(building.kind(),building.template(),level.getStructureManager().get(building.template()).orElseThrow());
        if(cache.templates().size()>=64) cache.templates().remove(cache.templates().keySet().iterator().next());cache.templates().put(building.template(),module);return module;
    }
    public static boolean loaded(ServerLevel level,BlockPos point) {return level.getChunkSource().hasChunk(point.getX()>>4,point.getZ()>>4);}
    public static boolean loaded(ServerLevel level,FunctionalBuilding building) {
        if(!building.dimension().equals(level.dimension().location().toString())) return false;
        for(int x=building.bounds().minX()>>4;x<=building.bounds().maxX()>>4;x++) for(int z=building.bounds().minZ()>>4;z<=building.bounds().maxZ()>>4;z++)
            if(!level.getChunkSource().hasChunk(x,z)) return false;return true;
    }
    public static boolean move(Villager worker,BlockPos point) {
        if(!(worker.level() instanceof ServerLevel level) || !loaded(level,point) || !level.getWorldBorder().isWithinBounds(point)) return false;
        WorkDoors.ensure(worker);return worker.getNavigation().moveTo(point.getX()+.5,point.getY(),point.getZ()+.5,.65);
    }
}

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
    private record Key(dev.livingkingdoms.structure.BuildingKind kind,ResourceLocation template) {}
    private record Cache(Object resources,Map<Key,Optional<BuildingTemplate>> templates) {}
    private static final Map<MinecraftServer,Cache> CACHES=new WeakHashMap<>();
    private WorkSites() {}
    public static BuildingTemplate template(ServerLevel level,FunctionalBuilding building) {
        return resolve(level,building).orElseThrow(()->new IllegalArgumentException("Unavailable or incompatible workplace: "+building.template()));
    }
    /** Invalid datapack modules block the job rather than crashing its entity tick or escaping its saved plot. */
    public static Optional<BuildingTemplate> resolve(ServerLevel level,FunctionalBuilding building) {
        var resources=level.getServer().getResourceManager();var cache=CACHES.get(level.getServer());
        if(cache==null || cache.resources()!=resources) {cache=new Cache(resources,new LinkedHashMap<>());CACHES.put(level.getServer(),cache);}
        var key=new Key(building.kind(),building.template());var known=cache.templates().get(key);
        if(known==null) {
            try {known=level.getStructureManager().get(building.template()).map(nativeTemplate->BuildingTemplate.validate(building.kind(),building.template(),nativeTemplate));}
            catch(RuntimeException invalid) {
                com.mojang.logging.LogUtils.getLogger().warn("Workplace module {} is unavailable; work paused until resource reload",building.template(),invalid);
                known=Optional.empty();
            }
            if(cache.templates().size()>=64) cache.templates().remove(cache.templates().keySet().iterator().next());cache.templates().put(key,known);
        }
        return known.filter(module->compatible(level,building,module));
    }
    public static boolean compatible(ServerLevel level,FunctionalBuilding building,BuildingTemplate module) {
        var size=module.rotatedSize(building.rotation());var bounds=building.bounds();
        return building.kind()==module.kind() && building.template().equals(module.id())
                && size.getX()==(long)bounds.maxX()-bounds.minX()+1 && size.getZ()==(long)bounds.maxZ()-bounds.minZ()+1
                && building.entrance().equals(module.worldPosition(module.entrance(),building.origin(),building.rotation()))
                && building.origin().getY()>=level.getMinBuildHeight() && (long)building.origin().getY()+size.getY()<=level.getMaxBuildHeight();
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

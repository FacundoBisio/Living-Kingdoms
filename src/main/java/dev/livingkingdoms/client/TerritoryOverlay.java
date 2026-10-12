package dev.livingkingdoms.client;

import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.ui.TerritoryOverlayGeometry;
import dev.livingkingdoms.ui.TerritoryOverlayService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.List;

/** Temporary vanilla line rendering, bounded to loaded nearby chunks. No blocks or entities are created. */
public final class TerritoryOverlay {
    private static Territory territory;
    private static int remainingTicks;
    private static int viewX=Integer.MIN_VALUE,viewZ=Integer.MIN_VALUE;
    private static List<TerritoryOverlayGeometry.CoveredChunk> chunks=List.of();
    private static List<TerritoryOverlayGeometry.Edge> circle=List.of(),visibleCircle=List.of();
    private TerritoryOverlay() {}

    public static void update(CompoundTag data) {
        clear();
        if(!data.hasUUID("settlement") || data.getInt("radius")<1 || data.getInt("duration")<1) return;
        try {
            territory=new Territory(data.getString("dimension"),data.getInt("x"),data.getInt("y"),data.getInt("z"),data.getInt("radius"));
        } catch(IllegalArgumentException ignored) { return; }
        remainingTicks=Math.min(TerritoryOverlayService.DURATION_TICKS,data.getInt("duration"));
        circle=TerritoryOverlayGeometry.circle(territory);
    }
    public static void clear() {
        territory=null; remainingTicks=0; viewX=Integer.MIN_VALUE; viewZ=Integer.MIN_VALUE;
        chunks=List.of(); circle=List.of(); visibleCircle=List.of();
    }
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void onTick(ClientTickEvent.Post event) {
        if(territory==null) return;
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null || !territory.dimension().equals(client.level.dimension().location().toString())) { clear(); return; }
        if(client.isPaused()) return;
        if(--remainingTicks<=0) { clear(); return; }
        var position=client.player.chunkPosition();
        if(position.x==viewX && position.z==viewZ) return;
        viewX=position.x; viewZ=position.z;
        chunks=TerritoryOverlayGeometry.nearbyChunks(territory,viewX,viewZ);
        int range=TerritoryOverlayGeometry.VIEW_CHUNK_RADIUS;
        double minX=((long)viewX-range)*16,minZ=((long)viewZ-range)*16;
        double maxX=((long)viewX+range+1)*16,maxZ=((long)viewZ+range+1)*16;
        visibleCircle=circle.stream().filter(edge -> Math.max(edge.x1(),edge.x2())>=minX && Math.min(edge.x1(),edge.x2())<=maxX
                && Math.max(edge.z1(),edge.z2())>=minZ && Math.min(edge.z1(),edge.z2())<=maxZ).toList();
    }
    public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_ENTITIES || territory==null) return;
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null || client.options.hideGui || !territory.dimension().equals(client.level.dimension().location().toString())) return;
        var camera=event.getCamera().getPosition(); var pose=event.getPoseStack();
        var buffers=client.renderBuffers().bufferSource(); var consumer=buffers.getBuffer(RenderType.lines());
        // A player-height slice stays visible without ground sampling, block reads or chunk loads.
        double y=Math.floor(client.player.getY())+.05-camera.y;
        for(var chunk:chunks) {
            if(client.level.getChunkSource().getChunk(chunk.x(),chunk.z(),ChunkStatus.FULL,false)==null) continue;
            double x=(long)chunk.x()*16-camera.x,z=(long)chunk.z()*16-camera.z;
            LevelRenderer.renderLineBox(pose,consumer,x,y,z,x+16,y+4,z+16,.12f,.78f,.95f,chunk.fullyInside()?.7f:.4f);
        }
        var transform=pose.last();
        int index=0;
        for(var edge:visibleCircle) {
            int chunkX=(int)Math.floor((edge.x1()+edge.x2())/32),chunkZ=(int)Math.floor((edge.z1()+edge.z2())/32);
            if(client.level.getChunkSource().getChunk(chunkX,chunkZ,ChunkStatus.FULL,false)==null) continue;
            float x1=(float)(edge.x1()-camera.x),z1=(float)(edge.z1()-camera.z);
            float x2=(float)(edge.x2()-camera.x),z2=(float)(edge.z2()-camera.z);
            float dx=x2-x1,dz=z2-z1,length=(float)Math.hypot(dx,dz);
            if(length==0) continue;
            consumer.addVertex(transform,x1,(float)y+.02f,z1).setColor(1f,.72f,.16f,1f).setNormal(transform,dx/length,0,dz/length);
            consumer.addVertex(transform,x2,(float)y+.02f,z2).setColor(1f,.72f,.16f,1f).setNormal(transform,dx/length,0,dz/length);
            if(index++%8==0) {
                consumer.addVertex(transform,x1,(float)y,z1).setColor(1f,.72f,.16f,.8f).setNormal(transform,0,1,0);
                consumer.addVertex(transform,x1,(float)y+4,z1).setColor(1f,.72f,.16f,.8f).setNormal(transform,0,1,0);
            }
        }
        buffers.endBatch(RenderType.lines());
    }
}

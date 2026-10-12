package dev.livingkingdoms.ui;

import dev.livingkingdoms.settlement.domain.Territory;
import java.util.ArrayList;
import java.util.List;

/** Pure geometry for the existing circular, block-position based territory. Never reads chunks. */
public final class TerritoryOverlayGeometry {
    public static final int VIEW_CHUNK_RADIUS=5;
    public static final int CIRCLE_SEGMENTS=256;
    public record CoveredChunk(int x,int z,boolean fullyInside) {}
    public record Edge(double x1,double z1,double x2,double z2) {}
    private TerritoryOverlayGeometry() {}

    /** A chunk is included iff at least one of its integer block positions belongs to the circle. */
    public static boolean intersects(Territory territory,int chunkX,int chunkZ) {
        long minX=(long)chunkX*16,minZ=(long)chunkZ*16;
        double nearestX=Math.clamp((double)territory.x(),minX,minX+15);
        double nearestZ=Math.clamp((double)territory.z(),minZ,minZ+15);
        double dx=nearestX-territory.x(),dz=nearestZ-territory.z();
        return dx*dx+dz*dz<=(double)territory.radius()*territory.radius();
    }

    public static boolean fullyInside(Territory territory,int chunkX,int chunkZ) {
        long minX=(long)chunkX*16,minZ=(long)chunkZ*16;
        double dx=Math.max(Math.abs(minX-(double)territory.x()),Math.abs(minX+15-(double)territory.x()));
        double dz=Math.max(Math.abs(minZ-(double)territory.z()),Math.abs(minZ+15-(double)territory.z()));
        return dx*dx+dz*dz<=(double)territory.radius()*territory.radius();
    }

    /** Maximum 121 candidates even for enormous imported territories. */
    public static List<CoveredChunk> nearbyChunks(Territory territory,int viewChunkX,int viewChunkZ) {
        var chunks=new ArrayList<CoveredChunk>();
        for(int dx=-VIEW_CHUNK_RADIUS;dx<=VIEW_CHUNK_RADIUS;dx++) {
            for(int dz=-VIEW_CHUNK_RADIUS;dz<=VIEW_CHUNK_RADIUS;dz++) {
                long x=(long)viewChunkX+dx,z=(long)viewChunkZ+dz;
                if(x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE) continue;
                if(intersects(territory,(int)x,(int)z)) chunks.add(new CoveredChunk((int)x,(int)z,fullyInside(territory,(int)x,(int)z)));
            }
        }
        return List.copyOf(chunks);
    }

    /** Polygon samples lie on the true radius; approximation does not redefine membership. */
    public static List<Edge> circle(Territory territory) {
        var edges=new ArrayList<Edge>(CIRCLE_SEGMENTS);
        for(int i=0;i<CIRCLE_SEGMENTS;i++) {
            double a=i*Math.TAU/CIRCLE_SEGMENTS,b=(i+1)*Math.TAU/CIRCLE_SEGMENTS;
            edges.add(new Edge(territory.x()+Math.cos(a)*territory.radius(),territory.z()+Math.sin(a)*territory.radius(),
                    territory.x()+Math.cos(b)*territory.radius(),territory.z()+Math.sin(b)*territory.radius()));
        }
        return List.copyOf(edges);
    }
}

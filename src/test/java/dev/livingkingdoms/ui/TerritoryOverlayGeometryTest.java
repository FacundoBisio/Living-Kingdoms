package dev.livingkingdoms.ui;

import dev.livingkingdoms.settlement.domain.Territory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerritoryOverlayGeometryTest {
    @Test void chunkMembershipMatchesActualTerritoryBlockPositionsIncludingNegativeCoordinates() {
        for(var territory:new Territory[]{circle(0,0,1),circle(-17,-5,19),circle(15,16,32),circle(100,-200,48)}) {
            int cx=Math.floorDiv(territory.x(),16),cz=Math.floorDiv(territory.z(),16);
            for(int x=cx-4;x<=cx+4;x++) for(int z=cz-4;z<=cz+4;z++) {
                boolean any=false,all=true;
                for(int bx=x*16;bx<x*16+16;bx++) for(int bz=z*16;bz<z*16+16;bz++) {
                    boolean inside=territory.contains(territory.dimension(),bx,bz); any|=inside; all&=inside;
                }
                assertEquals(any,TerritoryOverlayGeometry.intersects(territory,x,z));
                assertEquals(all,TerritoryOverlayGeometry.fullyInside(territory,x,z));
            }
        }
        assertFalse(TerritoryOverlayGeometry.intersects(circle(0,0,1),-1,-1));
        assertTrue(TerritoryOverlayGeometry.intersects(circle(0,0,1),-1,0));
    }
    @Test void nearbyCoverageIsBoundedAndDoesNotConfuseChunkSquareWithActualCircle() {
        var huge=circle(0,0,Integer.MAX_VALUE);
        assertEquals(121,TerritoryOverlayGeometry.nearbyChunks(huge,0,0).size());
        var territory=circle(0,0,32);
        assertFalse(territory.contains(territory.dimension(),31,31));
        assertTrue(TerritoryOverlayGeometry.intersects(territory,1,1));
        assertFalse(TerritoryOverlayGeometry.fullyInside(territory,1,1));
        assertTrue(TerritoryOverlayGeometry.nearbyChunks(territory,200,200).isEmpty());
    }
    @Test void circleSamplesUsePersistedCenterAndRadiusAndCloseExactly() {
        var territory=circle(-31,128,96); var edges=TerritoryOverlayGeometry.circle(territory);
        assertEquals(TerritoryOverlayGeometry.CIRCLE_SEGMENTS,edges.size());
        for(var edge:edges) {
            assertEquals(96,Math.hypot(edge.x1()+31,edge.z1()-128),1e-9);
            assertEquals(96,Math.hypot(edge.x2()+31,edge.z2()-128),1e-9);
        }
        assertEquals(edges.getFirst().x1(),edges.getLast().x2(),1e-9);
        assertEquals(edges.getFirst().z1(),edges.getLast().z2(),1e-9);
    }
    @Test void extremeCoordinatesDoNotOverflowIntoUnrelatedChunks() {
        var territory=circle(Integer.MAX_VALUE,Integer.MIN_VALUE,1);
        assertTrue(TerritoryOverlayGeometry.intersects(territory,Math.floorDiv(Integer.MAX_VALUE,16),Math.floorDiv(Integer.MIN_VALUE,16)));
        assertFalse(TerritoryOverlayGeometry.intersects(territory,Integer.MIN_VALUE,Integer.MAX_VALUE));
    }
    private static Territory circle(int x,int z,int radius) { return new Territory("minecraft:overworld",x,64,z,radius); }
}

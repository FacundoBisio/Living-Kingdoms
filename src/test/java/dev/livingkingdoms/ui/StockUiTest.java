package dev.livingkingdoms.ui;

import dev.livingkingdoms.economy.StockResource;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StockUiTest {
    @Test void stockTargetsAreUniqueStableAndRejectOtherIdentities() {
        var ids=new HashSet<UUID>();
        for(var resource:StockResource.values()) {
            var id=StockUiState.target(resource);assertTrue(ids.add(id));assertEquals(id,StockUiState.target(resource));
            assertEquals(resource,StockUiState.resource(id).orElseThrow());
        }
        assertEquals(UUID.fromString("12f9368d-3842-39e0-b0d1-8f8794071c35"),StockUiState.target(StockResource.WOOD));
        assertTrue(StockUiState.resource(new UUID(0,0)).isEmpty());assertTrue(StockUiState.resource(UUID.randomUUID()).isEmpty());
    }
    @Test void allResourcesAndEveryWrappedLineCanBeReachedWithoutOverlappingFooter() {
        for(int[] size:new int[][]{{280,200},{320,240},{427,240},{640,360},{854,480},{1920,1080}}) {
            var layout=StockUiLayout.fit(size[0],size[1]);var p=layout.panel();
            assertTrue(p.contentTop()+layout.rows()*26<=p.contentBottom());
            assertTrue(p.contentTop()+layout.lines()*11<=p.contentBottom());
            assertTrue(layout.actionWidth()>=47);
            assertTrue(p.detailX()+layout.actionWidth()*3+8<=p.left()+p.width()-8);
            var lines=java.util.stream.IntStream.range(0,200).boxed().toList();
            assertEquals(lines,DialoguePages.of(lines,layout.lines()).stream().flatMap(java.util.List::stream).toList());
            int lastOffset=Math.max(0,StockResource.values().length-layout.rows());
            assertTrue(lastOffset+layout.rows()>=StockResource.values().length);
        }
    }
}

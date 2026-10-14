package dev.livingkingdoms.ui;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class PickerPagesTest {
    @Test void allProfessionsAndEconomicBuildingsRemainReachableWithinSmallGuiBounds() {
        for(int width:new int[]{280,320,427,640,960}) for(int height:new int[]{200,240,360,540}) {
            var layout=VillageUiLayout.fit(width,height,true);int capacity=PickerPages.capacity(layout.viewport());
            assertTrue(26+(capacity/2-1)*24+20<=layout.viewport(),"Last button stays above footer");
            for(int items:new int[]{6,8}) {
                var reachable=new HashSet<Integer>();for(int page=0;page<PickerPages.count(items,layout.viewport());page++)
                    for(int slot=0;slot<capacity && page*capacity+slot<items;slot++) assertTrue(reachable.add(page*capacity+slot));
                assertEquals(items,reachable.size(),"Every choice has one reachable page");
            }
        }
    }
}

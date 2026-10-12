package dev.livingkingdoms.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KingdomHudLayoutTest {
    @Test void scalesAtUpperLeftClearOfBarsAndRightHandStatusEffects() {
        for(var size:new int[][]{{320,240},{427,240},{640,360},{960,540},{1920,1080}}) {
            var layout=KingdomHudLayout.of(size[0],size[1],90,7);
            assertTrue(layout.visible()); assertEquals(6,layout.x()); assertEquals(6,layout.y());
            assertTrue(layout.x()+layout.width()<size[0]/2);
            assertTrue(layout.y()+layout.height()<=size[1]-55);
        }
    }
    @Test void noContentOrInsufficientSpaceHidesRatherThanOverlappingVanilla() {
        assertFalse(KingdomHudLayout.of(320,240,90,0).visible());
        assertFalse(KingdomHudLayout.of(200,120,90,7).visible());
    }
    @Test void closedChatHeightCannotHideTheTrackedQuestAtVanillaGuiScale() {
        for(int chatHeight:new int[]{90,180,240,1000}) {
            var layout=KingdomHudLayout.of(320,240,chatHeight,7);
            assertTrue(layout.visible()); assertEquals(6,layout.y());
        }
    }
    @Test void bossBarsReserveSpaceOnlyWhenTheyOverlapTheLeftStrip() {
        var narrow=KingdomHudLayout.belowBossBars(320,240,50,7);
        assertTrue(narrow.visible()); assertEquals(53,narrow.y());
        assertEquals(6,KingdomHudLayout.belowBossBars(960,540,50,7).y());
    }
    @Test void shortcutDefaultsAreDistinctAndHaveTranslatedControlNames() {
        assertEquals(75,ShortcutAction.KINGDOM.defaultKey()); assertEquals(74,ShortcutAction.QUESTS.defaultKey());
        assertEquals(72,ShortcutAction.HUD.defaultKey());
        assertEquals(3,java.util.Arrays.stream(ShortcutAction.values()).map(ShortcutAction::titleKey).distinct().count());
    }
}

package dev.livingkingdoms.rpg;

import dev.livingkingdoms.rpg.domain.Fortification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 0.38: wall progress percent shown in the Walls tab and the HUD. */
class WallProgressTest {
    @Test void percentIsClampedAndCompleteIsFull() {
        assertEquals(0, Fortification.percent(0, 0, false), "No ring yet means no progress");
        assertEquals(0, Fortification.percent(0, 120, false));
        assertEquals(50, Fortification.percent(60, 120, false));
        assertEquals(66, Fortification.percent(2, 3, false), "Rounds down so a rising wall never reads 100%");
        assertEquals(100, Fortification.percent(500, 120, false), "Clamped to 100");
        assertEquals(0, Fortification.percent(-4, 120, false), "Clamped to 0");
        assertEquals(100, Fortification.percent(3, 120, true), "A finished wall is always 100%");
        assertEquals(100, Fortification.percent(0, 0, true));
    }
    @Test void hudShowsPercentAndToleratesOlderServers() {
        var local = new net.minecraft.nbt.CompoundTag(); local.putString("name", "Realm"); local.putInt("wall_building", 1); local.putInt("wall_percent", 164);
        var data = new net.minecraft.nbt.CompoundTag(); data.put("settlement", local);
        var row = wallRow(data);
        assertEquals("hud.livingkingdoms.wall_building_percent", row.getKey());
        assertEquals(100, row.getArgs()[0], "Percent is clamped on the client too");
        local.remove("wall_percent");
        assertEquals("hud.livingkingdoms.wall_building", wallRow(data).getKey());
    }
    private static net.minecraft.network.chat.contents.TranslatableContents wallRow(net.minecraft.nbt.CompoundTag data) {
        return dev.livingkingdoms.ui.KingdomHudPresentation.describe(data, false, true, false).kingdom().stream()
                .map(r -> r.text().getContents()).filter(net.minecraft.network.chat.contents.TranslatableContents.class::isInstance)
                .map(net.minecraft.network.chat.contents.TranslatableContents.class::cast)
                .filter(c -> c.getKey().startsWith("hud.livingkingdoms.wall_building")).findFirst().orElseThrow();
    }
}

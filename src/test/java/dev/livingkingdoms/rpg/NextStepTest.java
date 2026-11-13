package dev.livingkingdoms.rpg;

import dev.livingkingdoms.construction.domain.ConstructionProject;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.ui.KingdomHudPresentation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static dev.livingkingdoms.rpg.QuestGuides.Next.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.38: the construction next step shown in the HUD and the site checklist. */
class NextStepTest {
    @Test void nextStepPriority() {
        assertEquals(REASON, QuestGuides.next(true, false, true, false, false), "A stall reason wins over everything");
        assertEquals(NEEDS, QuestGuides.next(false, false, true, false, false));
        assertEquals(BUILDER, QuestGuides.next(false, true, true, false, false));
        assertEquals(NONE, QuestGuides.next(false, true, false, false, false), "Settlers start by themselves");
        assertEquals(BUILDING, QuestGuides.next(false, true, true, true, true));
    }
    @Test void siteChecklistDropsReturnStepAndTracksState() {
        var project = ConstructionProject.planned(UUID.randomUUID(), BuildingKind.HOUSE, new ConstructionProject.Plot(0, 64, 0, ConstructionProject.Orientation.NONE),
                Map.of(ResourceKind.LOGS, 8), 100, 0).requiringBuilder().waitForResources();
        var open = QuestGuides.steps(project, false);
        assertEquals(4, open.size(), "plan, supply, builder, wait");
        assertTrue(open.getCompound(0).getBoolean("done"));
        assertFalse(open.getCompound(1).getBoolean("done"));
        assertEquals("quest.livingkingdoms.step.development.builder", open.getCompound(2).getString("key"));
        assertEquals(5, QuestGuides.steps(project, true).size(), "The quest keeps its return step");
        assertTrue(QuestGuides.steps(project.supply(Map.of(ResourceKind.LOGS, 8)), false).getCompound(1).getBoolean("done"));
    }
    @Test void hudSiteRowPerKind() {
        var site = new CompoundTag(); site.putString("building", "house"); site.putInt("percent", 64); site.putInt("helpers", 1);
        assertEquals("hud.livingkingdoms.site", key(site));
        site.putString("next", "builder");
        assertEquals("hud.livingkingdoms.site_builder", key(site));
        site.putString("next", "needs"); var missing = new ListTag();
        for (var entry : new String[][]{{"wood", "12"}, {"stone", "4"}, {"iron", "2"}}) {
            var m = new CompoundTag(); m.putString("item", entry[0]); m.putInt("count", Integer.parseInt(entry[1])); missing.add(m);
        }
        site.put("missing", missing);
        var needs = row(site);
        assertEquals("hud.livingkingdoms.site_needs", ((TranslatableContents) needs.getContents()).getKey());
        var list = (Component) ((TranslatableContents) needs.getContents()).getArgs()[0];
        assertEquals(1, countOf(list.getString(), ','), "Only two resources are listed, then an ellipsis: " + list.getString());
        assertTrue(list.getString().endsWith("\u2026") && list.getString().startsWith("12 "));
        site.putString("next", "reason"); site.putString("reason", "construction.livingkingdoms.reason.obstacle"); site.putBoolean("blocked", true);
        var args = new ListTag(); args.add(StringTag.valueOf("Chest")); args.add(StringTag.valueOf("1 2 3")); site.put("reason_args", args);
        var reason = (TranslatableContents) row(site).getContents();
        assertEquals("construction.livingkingdoms.reason.obstacle", reason.getKey());
        assertEquals("Chest", reason.getArgs()[0]);
        site.putString("reason", "evil.key");
        assertEquals("hud.livingkingdoms.site_blocked", key(site), "Unknown reason keys fall back to the old row");
    }
    @Test void chapterShownOnlyWithoutTrackedQuest() {
        var chapter = new CompoundTag(); chapter.putString("id", "palisade"); chapter.putInt("target", 1); chapter.putBoolean("accepted", true); chapter.putInt("progress", 0);
        var data = new CompoundTag(); data.put("chapter", chapter);
        var rows = KingdomHudPresentation.describe(data, false, true, true).quest();
        assertEquals(2, rows.size());
        assertEquals("hud.livingkingdoms.chapter", ((TranslatableContents) rows.getFirst().text().getContents()).getKey());
        var quest = new CompoundTag(); quest.putString("template", "main_residents"); data.put("quest", quest);
        assertTrue(KingdomHudPresentation.describe(data, false, true, true).quest().stream()
                .noneMatch(r -> r.text().getContents() instanceof TranslatableContents t && t.getKey().equals("hud.livingkingdoms.chapter")));
    }
    @Test void bearingRowOnlyWithTarget() {
        var quest = new CompoundTag(); quest.putString("template", "pillager_request");
        var data = new CompoundTag(); data.put("quest", quest);
        int plain = KingdomHudPresentation.describe(data, false, false, true, Component.literal("x")).quest().size();
        quest.putInt("target_x", 1); quest.putInt("target_z", 2);
        assertEquals(plain + 1, KingdomHudPresentation.describe(data, false, false, true, Component.literal("x")).quest().size());
        assertEquals(plain, KingdomHudPresentation.describe(data, false, false, true, null).quest().size());
    }
    private static int countOf(String text, char c) { return (int) text.chars().filter(x -> x == c).count(); }
    private static String key(CompoundTag site) { return ((TranslatableContents) row(site).getContents()).getKey(); }
    private static Component row(CompoundTag site) {
        var local = new CompoundTag(); local.putString("name", "Realm"); local.put("site", site);
        var data = new CompoundTag(); data.put("settlement", local);
        return KingdomHudPresentation.describe(data, false, true, false).kingdom().stream()
                .map(r -> r.text()).filter(t -> t.getContents() instanceof TranslatableContents c && (c.getKey().startsWith("hud.livingkingdoms.site") || c.getKey().startsWith("construction.livingkingdoms.reason")))
                .findFirst().orElseThrow();
    }
}

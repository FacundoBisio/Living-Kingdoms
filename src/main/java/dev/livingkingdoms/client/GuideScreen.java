package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.ShortcutAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Client-only "How do I...?" help: four short recipes naming the real buttons and the player's actual key bindings. */
final class GuideScreen extends KingdomScreen {
    private record Row(FormattedCharSequence text, int color) {}
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int left, top, panelWidth, panelHeight, scroll;
    private VillageButton up, down;

    GuideScreen(Screen parent) {
        super(Component.translatable("guide.livingkingdoms.title"));
        this.parent = parent;
    }

    private static Component label(String key) { return Component.translatable(key); }
    /** Reads the live binding so remapped controls show correctly. */
    private static Component key(ShortcutAction action) {
        for (var mapping : Minecraft.getInstance().options.keyMappings) if (mapping.getName().equals(action.titleKey())) return mapping.getTranslatedKeyMessage();
        return Component.literal("?");
    }

    @Override protected void init() {
        if (!initReadableSize()) return;
        panelWidth = Math.min(380, width - 20); panelHeight = Math.min(250, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        int w = panelWidth - 24 - 24; // room for the scroll buttons
        rows.clear();
        entry("walls", w, label("management.livingkingdoms.walls"), label("management.livingkingdoms.walls_preview"));
        entry("building", w, label("ui.livingkingdoms.construction"), label("ui.livingkingdoms.choose_building"), label("management.livingkingdoms.preview"), label("ui.livingkingdoms.citizens"));
        entry("mission", w, key(ShortcutAction.QUESTS), label("ui.livingkingdoms.track"), key(ShortcutAction.HUD), key(ShortcutAction.MAP), label("ui.livingkingdoms.chapters"));
        entry("trial", w, label("ui.livingkingdoms.challenges"));
        addRenderableWidget(new VillageButton("guide_back", label("ui.livingkingdoms.back"), left + 10, top + panelHeight - 26, 70, this::onClose));
        up = addRenderableWidget(new VillageButton("guide_up", label("ui.livingkingdoms.scroll_up"), left + panelWidth - 30, top + 28, 20, () -> scroll(-3)).glyph("^"));
        down = addRenderableWidget(new VillageButton("guide_down", label("ui.livingkingdoms.scroll_down"), left + panelWidth - 30, top + 51, 20, () -> scroll(3)).glyph("v"));
        scroll(0);
    }

    private void entry(String id, int w, Object... args) {
        rows.add(new Row(Component.translatable("guide.livingkingdoms." + id + ".title").withStyle(net.minecraft.ChatFormatting.BOLD).getVisualOrderText(), VillageTheme.INK));
        for (var line : font.split(Component.translatable("guide.livingkingdoms." + id + ".text", args), w)) rows.add(new Row(line, VillageTheme.MUTED));
        rows.add(new Row(FormattedCharSequence.EMPTY, 0));
    }

    private int visibleRows() { return Math.max(1, (panelHeight - 34 - 32) / 10); }
    private void scroll(int delta) {
        scroll = Math.clamp(scroll + delta, 0, Math.max(0, rows.size() - visibleRows()));
        if (up != null) { up.active = scroll > 0; down.active = scroll < rows.size() - visibleRows(); up.visible = down.visible = rows.size() > visibleRows(); }
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { scroll(dy > 0 ? -1 : 1); return true; }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(g, mouseX, mouseY, delta)) return;
        renderBackdrop(g);
        RpgFrame.panel(g, left, top, panelWidth, panelHeight);
        RpgFrame.header(g, left + 2, top + 2, panelWidth - 4, 20);
        g.drawString(font, title, left + 10, top + 7, VillageTheme.ON_BLUE, false);
        int y = top + 30;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++, y += 10) g.drawString(font, rows.get(i).text(), left + 12, y, rows.get(i).color(), false);
        super.render(g, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

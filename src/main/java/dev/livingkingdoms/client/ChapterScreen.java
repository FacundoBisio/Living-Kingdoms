package dev.livingkingdoms.client;

import dev.livingkingdoms.rpg.RpgPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Chapter 2 "A Growing Kingdom": the current mission with its goal, progress bar and rewards, plus the list of
 * missions done and coming. Accept and claim are server-validated; every reply is a fresh snapshot.
 */
final class ChapterScreen extends KingdomScreen {
    private final VillageScreen parent;
    private CompoundTag data;
    private boolean pending;
    private int left, top, panelWidth, panelHeight;

    ChapterScreen(VillageScreen parent, CompoundTag data) {
        super(Component.translatable("chapter.livingkingdoms.title"));
        this.parent = parent; this.data = data.copy();
    }
    void update(CompoundTag next) { data = next.copy(); pending = false; rebuildWidgets(); }
    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) { return Component.translatable("chapter.livingkingdoms." + key, args); }
    private CompoundTag mission(int i) { var l = data.getList("missions", 10); return i >= 0 && i < l.size() ? l.getCompound(i) : new CompoundTag(); }

    @Override protected void init() {
        if (!initReadableSize()) return;
        panelWidth = Math.min(380, width - 20); panelHeight = Math.min(250, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        int next = data.getInt("next"); boolean accepted = data.getInt("accepted") == next;
        var m = mission(next); boolean ready = accepted && m.getInt("progress") >= m.getInt("target");
        var action = addRenderableWidget(new VillageButton("chapter_action", tr(accepted ? "claim" : "accept"), left + panelWidth - 112, top + panelHeight - 26, 102,
                () -> { pending = true; rebuildWidgets(); PacketDistributor.sendToServer(new RpgPayloads.ChapterAction(next, accepted)); }));
        action.active = !pending && data.getBoolean("available") && next < data.getList("missions", 10).size() && (!accepted || ready);
        if (action.active) action.primary();
        if (!data.getBoolean("available")) action.setTooltip(Tooltip.create(tr("unavailable")));
        addRenderableWidget(new VillageButton("chapter_back", Component.translatable("ui.livingkingdoms.back"), left + 10, top + panelHeight - 26, 70, () -> minecraft.setScreen(parent)));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(g, mouseX, mouseY, delta)) return;
        renderBackdrop(g);
        RpgFrame.panel(g, left, top, panelWidth, panelHeight);
        RpgFrame.header(g, left + 2, top + 2, panelWidth - 4, 20);
        g.drawString(font, title, left + 10, top + 7, VillageTheme.ON_BLUE, false);
        var missions = data.getList("missions", 10); int next = data.getInt("next");
        var count = tr("count", Math.min(next, missions.size()), missions.size());
        g.drawString(font, count, left + panelWidth - 10 - font.width(count), top + 7, VillageTheme.GOLD, false);
        int x = left + 12, w = panelWidth - 24, y = top + 30;
        if (next >= missions.size()) {
            for (var line : font.split(tr("complete"), w)) { g.drawString(font, line, x, y, VillageTheme.SUCCESS, false); y += 10; }
        } else {
            var m = mission(next); String id = m.getString("id"); boolean accepted = data.getInt("accepted") == next;
            g.drawString(font, tr(id + ".title").withStyle(net.minecraft.ChatFormatting.BOLD), x, y, VillageTheme.INK, false); y += 12;
            for (var line : font.split(tr(id + ".story"), w)) { g.drawString(font, line, x, y, VillageTheme.MUTED, false); y += 10; }
            y += 4;
            int target = Math.max(1, m.getInt("target")), progress = Math.min(target, m.getInt("progress"));
            g.drawString(font, tr(id + ".goal", target), x, y, VillageTheme.INK, false); y += 11;
            if (accepted) {
                g.fill(x, y, x + w, y + 6, VillageTheme.INSET);
                g.fill(x, y, x + w * progress / target, y + 6, progress >= target ? VillageTheme.SUCCESS : 0xFFD3AA53);
                var p = Component.literal(progress + "/" + target);
                g.drawString(font, p, x + w - font.width(p), y + 8, VillageTheme.MUTED, false);
                y += 20;
            } else { g.drawString(font, tr("not_accepted"), x, y, VillageTheme.MUTED, false); y += 14; }
            // Rewards as item cards.
            g.drawString(font, tr("rewards"), x, y, VillageTheme.INK, false); y += 11;
            var rewards = new java.util.ArrayList<ItemStack>();
            rewards.add(new ItemStack(Items.EMERALD, m.getInt("emeralds")));
            rewards.add(new ItemStack(Items.CHEST));
            if (m.getInt("whetstones") > 0) rewards.add(new ItemStack(dev.livingkingdoms.item.KingdomItems.ROYAL_WHETSTONE.get(), m.getInt("whetstones")));
            int cx = x;
            for (var stack : rewards) { RpgFrame.card(g, cx, y, 24, 24); g.renderItem(stack, cx + 4, y + 4); g.renderItemDecorations(font, stack, cx + 4, y + 4); cx += 28; }
            g.drawString(font, tr("reward_line", Component.translatable("item.livingkingdoms.kingdom_chest_" + m.getString("chest")), m.getInt("xp")), cx + 4, y + 8, VillageTheme.MUTED, false);
            y += 32;
        }
        // Chapter outline.
        g.drawString(font, tr("outline"), x, y, VillageTheme.INK, false); y += 11;
        for (int i = 0; i < missions.size() && y < top + panelHeight - 36; i++) {
            var m = missions.getCompound(i);
            int color = m.getBoolean("claimed") ? VillageTheme.SUCCESS : i == next ? VillageTheme.INK : VillageTheme.MUTED;
            var mark = m.getBoolean("claimed") ? "✔ " : i == next ? "▶ " : "• ";
            g.drawString(font, Component.literal(mark).append(tr(m.getString("id") + ".title")), x + (i % 2) * (w / 2), y, color, false);
            if (i % 2 == 1) y += 10;
        }
        super.render(g, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

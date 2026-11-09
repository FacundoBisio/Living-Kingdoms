package dev.livingkingdoms.client;

import dev.livingkingdoms.rpg.ContractService;
import dev.livingkingdoms.rpg.RpgPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Daily/weekly contracts with streak, kingdom rank bar and the all-dailies bonus chest. Display only:
 * the server decides completion and pays; claims return a fresh snapshot.
 */
final class ContractScreen extends KingdomScreen {
    private final VillageScreen parent;
    private CompoundTag data;
    private boolean pending;
    private int left, top, panelWidth, panelHeight;

    ContractScreen(VillageScreen parent, CompoundTag data) {
        super(Component.translatable("ui.livingkingdoms.contracts"));
        this.parent = parent; this.data = data.copy();
    }

    void update(CompoundTag next) { data = next.copy(); pending = false; rebuildWidgets(); }

    @Override protected void init() {
        if (!initReadableSize()) return;
        panelWidth = Math.min(360, width - 20); panelHeight = Math.min(250, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        boolean inside = data.getBoolean("inside");
        var daily = data.getList("daily", 10);
        for (int i = 0; i < daily.size(); i++) {
            var c = daily.getCompound(i); final int slot = i;
            claimButton("contract_" + i, c, rowY(i), () -> claim(slot), inside);
        }
        if (data.contains("weekly", 10)) claimButton("contract_weekly", data.getCompound("weekly"), rowY(5), () -> claim(ContractService.SLOT_WEEKLY), inside);
        var bonus = addRenderableWidget(new VillageButton("contract_bonus", Component.translatable(data.getBoolean("bonus_claimed")
                ? "ui.livingkingdoms.contracts.bonus_done" : "ui.livingkingdoms.contracts.bonus"),
                left + panelWidth - 132, top + panelHeight - 26, 122, () -> claim(ContractService.SLOT_BONUS)));
        bonus.active = !pending && inside && data.getBoolean("bonus_ready");
        if (bonus.active) bonus.primary();
        bonus.setTooltip(Tooltip.create(Component.translatable("ui.livingkingdoms.contracts.bonus_help")));
        addRenderableWidget(new VillageButton("contract_back", Component.translatable("ui.livingkingdoms.back"),
                left + 10, top + panelHeight - 26, 70, () -> minecraft.setScreen(parent)));
    }

    private int rowY(int index) { return top + 58 + (index >= 5 ? 4 * 27 + 8 : index * 27); }

    private void claimButton(String key, CompoundTag c, int y, Runnable action, boolean inside) {
        var button = addRenderableWidget(new VillageButton(key, Component.translatable(c.getBoolean("claimed") ? "ui.livingkingdoms.contracts.done"
                : "ui.livingkingdoms.claim"), left + panelWidth - 82, y, 72, action));
        button.active = !pending && inside && c.getBoolean("ready");
        if (button.active) button.primary();
        if (!inside) button.setTooltip(Tooltip.create(Component.translatable("ui.livingkingdoms.contracts.go_home")));
    }

    private void claim(int slot) {
        pending = true; rebuildWidgets();
        PacketDistributor.sendToServer(new RpgPayloads.ContractClaim(slot));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(g, mouseX, mouseY, delta)) return;
        renderBackdrop(g);
        RpgFrame.panel(g, left, top, panelWidth, panelHeight);
        RpgFrame.header(g, left + 2, top + 2, panelWidth - 4, 20);
        g.drawString(font, title, left + 10, top + 7, VillageTheme.ON_BLUE, false);
        var streak = Component.translatable("ui.livingkingdoms.contracts.streak", data.getInt("streak"), data.getInt("multiplier"));
        g.drawString(font, streak, left + panelWidth - 10 - font.width(streak), top + 7, VillageTheme.GOLD, false);
        // Kingdom rank bar.
        long span = Math.max(1, data.getLong("rank_span")), progress = Math.clamp(data.getLong("rank_progress"), 0, span);
        g.drawString(font, Component.translatable("ui.livingkingdoms.contracts.rank", data.getInt("rank"), progress, span), left + 10, top + 28, VillageTheme.INK, false);
        int barX = left + 10, barY = top + 40, barW = panelWidth - 20;
        g.fill(barX, barY, barX + barW, barY + 6, VillageTheme.INSET);
        g.fill(barX, barY, barX + (int) (barW * (double) progress / span), barY + 6, 0xFF6DA862);
        var daily = data.getList("daily", 10);
        g.drawString(font, Component.translatable("ui.livingkingdoms.contracts.daily", (data.getLong("reset") + 3599) / 3600), left + 10, top + 49, VillageTheme.MUTED, false);
        if (data.getBoolean("festival")) {
            var festival = Component.translatable("ui.livingkingdoms.contracts.festival");
            g.drawString(font, festival, left + panelWidth - 10 - font.width(festival), top + 49, VillageTheme.GOLD, false);
        }
        // Learned techniques: a compact badge with a hover list.
        var known = data.getList("techniques", 8);
        var badge = Component.translatable("ui.livingkingdoms.contracts.techniques", known.size());
        int badgeX = left + panelWidth - 10 - font.width(badge);
        g.drawString(font, badge, badgeX, top + 28, known.isEmpty() ? VillageTheme.MUTED : 0xFF6DA862, false);
        if (mouseX >= badgeX && mouseX <= badgeX + font.width(badge) && mouseY >= top + 26 && mouseY <= top + 38) {
            var lines = new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
            lines.add(Component.translatable("ui.livingkingdoms.contracts.techniques_title").withStyle(net.minecraft.ChatFormatting.GOLD).getVisualOrderText());
            if (known.isEmpty()) lines.add(Component.translatable("ui.livingkingdoms.contracts.techniques_none").withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
            for (int i = 0; i < known.size(); i++) {
                var id = known.getString(i);
                lines.add(Component.literal("✦ ").append(Component.translatable("technique.livingkingdoms." + id)).withStyle(net.minecraft.ChatFormatting.GREEN).getVisualOrderText());
                lines.addAll(font.split(Component.translatable("technique.livingkingdoms." + id + ".effect").withStyle(net.minecraft.ChatFormatting.GRAY), 200));
            }
            pendingTooltip = lines;
        }
        for (int i = 0; i < daily.size(); i++) row(g, daily.getCompound(i), rowY(i), new ItemStack(Items.PAPER));
        int weeklyY = rowY(5);
        g.drawString(font, Component.translatable("ui.livingkingdoms.contracts.weekly"), left + 10, weeklyY - 10, VillageTheme.MUTED, false);
        if (data.contains("weekly", 10)) row(g, data.getCompound("weekly"), weeklyY, new ItemStack(Items.WRITABLE_BOOK));
        else g.drawString(font, Component.translatable("ui.livingkingdoms.contracts.weekly_locked"), left + 30, weeklyY + 6, VillageTheme.MUTED, false);
        super.render(g, mouseX, mouseY, delta);
        if (pendingTooltip != null) { g.renderTooltip(font, pendingTooltip, mouseX, mouseY); pendingTooltip = null; }
    }
    private java.util.List<net.minecraft.util.FormattedCharSequence> pendingTooltip;

    private void row(GuiGraphics g, CompoundTag c, int y, ItemStack icon) {
        boolean claimed = c.getBoolean("claimed"), ready = c.getBoolean("ready");
        g.fill(left + 6, y - 2, left + panelWidth - 6, y + 22, claimed ? VillageTheme.DISABLED : VillageTheme.PAPER);
        g.fill(left + 6, y - 2, left + 9, y + 22, claimed ? VillageTheme.LOCKED : ready ? 0xFF6DA862 : 0xFFD3AA53);
        g.renderItem(icon, left + 12, y + 1);
        var description = c.getString("kind").equals("deliver")
                ? Component.translatable("contract.livingkingdoms.kind.deliver", c.getInt("required"), Component.translatable("contract.livingkingdoms.resource." + c.getString("target")))
                : Component.translatable("contract.livingkingdoms.kind." + c.getString("kind"), c.getInt("required"));
        int textWidth = panelWidth - 130;
        g.drawString(font, font.plainSubstrByWidth(description.getString(), textWidth), left + 32, y + 1, VillageTheme.INK, false);
        int required = Math.max(1, c.getInt("required")), progress = Math.clamp(c.getInt("progress"), 0, required);
        g.fill(left + 32, y + 12, left + 32 + textWidth, y + 16, VillageTheme.INSET);
        g.fill(left + 32, y + 12, left + 32 + textWidth * progress / required, y + 16, ready || claimed ? VillageTheme.SUCCESS : 0xFFD3AA53);
        g.drawString(font, progress + "/" + required + "  +" + c.getInt("emeralds") + "◆", left + 32 + textWidth - 70, y + 1, VillageTheme.MUTED, false);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

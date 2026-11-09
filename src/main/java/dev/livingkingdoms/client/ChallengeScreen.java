package dev.livingkingdoms.client;

import dev.livingkingdoms.rpg.RpgPayloads;
import dev.livingkingdoms.rpg.domain.ChallengeTier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Trial picker opened from the Quest Board. Shows each difficulty, its waves, boss and rewards, and
 * sends only the chosen tier; the server re-validates unlocks, cooldown, territory and safety.
 */
final class ChallengeScreen extends KingdomScreen {
    private static final ItemStack[] ICONS = {new ItemStack(Items.IRON_SWORD), new ItemStack(Items.CROSSBOW), new ItemStack(Items.NETHERITE_AXE)};
    private final VillageScreen parent;
    private final CompoundTag data;
    private int left, top, panelWidth, panelHeight;

    ChallengeScreen(VillageScreen parent, CompoundTag data) {
        super(Component.translatable("ui.livingkingdoms.challenges"));
        this.parent = parent; this.data = data.copy();
    }

    @Override protected void init() {
        if (!initReadableSize()) return;
        panelWidth = Math.min(340, width - 20); panelHeight = Math.min(236, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        var tiers = data.getList("tiers", 10);
        int rowHeight = (panelHeight - 64) / 3;
        boolean blocked = data.getLong("cooldown") > 0 || data.getBoolean("active");
        for (int i = 0; i < Math.min(3, tiers.size()); i++) {
            var tier = tiers.getCompound(i);
            var id = ChallengeTier.fromId(tier.getString("id"));
            int y = top + 28 + i * rowHeight + rowHeight - 24;
            var start = addRenderableWidget(new VillageButton("challenge_" + id.id(), Component.translatable("ui.livingkingdoms.challenges.start"),
                    left + panelWidth - 92, y, 80, () -> begin(id)));
            start.active = tier.getBoolean("unlocked") && !blocked;
            if (start.active) start.primary();
            start.setTooltip(Tooltip.create(tier.getBoolean("unlocked")
                    ? Component.translatable("ui.livingkingdoms.challenges.warning")
                    : Component.translatable("ui.livingkingdoms.challenges.requires", Component.translatable(tier.getString("requires")))));
        }
        addRenderableWidget(new VillageButton("challenge_back", Component.translatable("ui.livingkingdoms.back"),
                left + 10, top + panelHeight - 26, 80, () -> minecraft.setScreen(parent)));
    }

    private void begin(ChallengeTier tier) {
        PacketDistributor.sendToServer(new RpgPayloads.ChallengeStart(tier));
        parent.onClose();
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(g, mouseX, mouseY, delta)) return;
        renderBackdrop(g);
        RpgFrame.panel(g, left, top, panelWidth, panelHeight);
        RpgFrame.header(g, left + 2, top + 2, panelWidth - 4, 20);
        g.drawString(font, title, left + 10, top + 7, VillageTheme.ON_BLUE, false);
        long cooldown = data.getLong("cooldown");
        if (data.getBoolean("active")) g.drawString(font, Component.translatable("ui.livingkingdoms.challenges.active"), left + 120, top + 7, VillageTheme.GOLD, false);
        else if (cooldown > 0) g.drawString(font, Component.translatable("ui.livingkingdoms.challenges.cooldown", (cooldown + 59) / 60), left + 120, top + 7, VillageTheme.GOLD, false);
        var tiers = data.getList("tiers", 10);
        int rowHeight = (panelHeight - 64) / 3;
        for (int i = 0; i < Math.min(3, tiers.size()); i++) {
            var tier = tiers.getCompound(i);
            int y = top + 28 + i * rowHeight;
            boolean unlocked = tier.getBoolean("unlocked");
            g.fill(left + 6, y, left + panelWidth - 6, y + rowHeight - 4, unlocked ? VillageTheme.PAPER : VillageTheme.DISABLED);
            g.fill(left + 6, y, left + 9, y + rowHeight - 4, unlocked ? new int[]{0xFF6DA862, 0xFFD3AA53, 0xFF922C22}[i] : VillageTheme.LOCKED);
            g.renderItem(ICONS[i], left + 14, y + 6);
            g.drawString(font, Component.translatable("challenge.livingkingdoms.tier." + tier.getString("id")).withStyle(net.minecraft.ChatFormatting.BOLD),
                    left + 36, y + 5, VillageTheme.INK, false);
            int textWidth = panelWidth - 140;
            var summary = Component.translatable(tier.getBoolean("boss") ? "ui.livingkingdoms.challenges.summary_boss" : "ui.livingkingdoms.challenges.summary",
                    tier.getInt("waves"), tier.getInt("enemies"), tier.getInt("minutes"));
            g.drawString(font, font.plainSubstrByWidth(summary.getString(), textWidth), left + 36, y + 16, VillageTheme.MUTED, false);
            var rewards = Component.translatable("ui.livingkingdoms.challenges.rewards", tier.getInt("emeralds"), tier.getInt("whetstones"), tier.getInt("tomes"));
            if (!tier.getString("reward").equals("none"))
                rewards = rewards.append(" + ").append(Component.translatable("item.livingkingdoms." + (tier.getString("reward").equals("champion_axe") ? "champion_axe" : "warlord_blade")));
            g.drawString(font, font.plainSubstrByWidth(rewards.getString(), textWidth), left + 36, y + 27, unlocked ? VillageTheme.SUCCESS : VillageTheme.MUTED, false);
        }
        super.render(g, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

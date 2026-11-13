package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.UUID;

/**
 * Conversation with one resident: portrait, a line of small talk, their job level and what the next level
 * changes, the bond with you and today's wish (give it from any inventory slot), and shortcuts to give them a
 * job or send them on an expedition. All actions are server-validated through the citizens session.
 */
public final class ResidentScreen extends KingdomScreen {
    private CompoundTag data;
    private final UUID resident;
    private int mode; // 0 talk, 1 jobs, 2 expeditions
    private boolean pending;
    private int left, top, panelW, panelH;

    public ResidentScreen(CompoundTag data) {
        super(Component.empty());
        this.data = data.copy(); this.resident = data.getUUID("focus");
    }
    public boolean sameSession(CompoundTag next) { return next.hasUUID("session") && data.getUUID("session").equals(next.getUUID("session")); }
    public void update(CompoundTag next) { data = next.copy(); pending = false; if (next.contains("notice")) mode = 0; rebuildWidgets(); }

    private CompoundTag person() {
        var list = data.getList("citizens", 10);
        for (int i = 0; i < list.size(); i++) if (list.getCompound(i).getUUID("id").equals(resident)) return list.getCompound(i);
        return new CompoundTag();
    }
    private static Component tr(String key, Object... args) { return Component.translatable("resident.livingkingdoms." + key, args); }
    private void send(UiPayloads.Action action) {
        if (pending) return; pending = true;
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"), resident, action));
        rebuildWidgets();
    }

    @Override protected void init() {
        if (!initReadableSize()) return;
        panelW = Math.min(width - 16, 400); panelH = Math.min(height - 16, 232);
        left = (width - panelW) / 2; top = (height - panelH) / 2;
        var p = person();
        int x = left + 104, w = panelW - 114, y = top + panelH - 28, bw = (w - 12) / 4;
        var give = addRenderableWidget(new VillageButton("give", tr("give"), x, y, bw, () -> send(UiPayloads.Action.GIVE_WISH)));
        give.active = !pending && !p.getBoolean("wish_done") && p.getBoolean("wish_have");
        if (give.active) give.primary();
        give.setTooltip(Tooltip.create(tr(p.getBoolean("wish_done") ? "give_done" : p.getBoolean("wish_have") ? "give_help" : "give_missing")));
        var jobs = addRenderableWidget(new VillageButton("jobs", tr(mode == 1 ? "back" : "jobs"), x + bw + 4, y, bw, () -> { mode = mode == 1 ? 0 : 1; rebuildWidgets(); }));
        jobs.active = !pending && !p.getString("profession").equals("mayor");
        var trips = addRenderableWidget(new VillageButton("trips", tr(mode == 2 ? "back" : "expedition"), x + (bw + 4) * 2, y, bw, () -> { mode = mode == 2 ? 0 : 2; rebuildWidgets(); }));
        trips.active = !pending && p.getBoolean("can_expedition");
        trips.setTooltip(Tooltip.create(Component.translatable(trips.active ? "ui.livingkingdoms.expedition.help" : "ui.livingkingdoms.expedition.requirement")));
        addRenderableWidget(new VillageButton("roster", tr("roster"), x + (bw + 4) * 3, y, bw, () -> send(UiPayloads.Action.CITIZENS))).active = !pending;
        if (mode == 1) {
            String[] types = {"farmer", "guard", "builder", "lumberjack", "miner", "blacksmith", "merchant"};
            UiPayloads.Action[] actions = {UiPayloads.Action.ASSIGN_FARMER, UiPayloads.Action.ASSIGN_GUARD, UiPayloads.Action.ASSIGN_BUILDER, UiPayloads.Action.ASSIGN_LUMBERJACK,
                    UiPayloads.Action.ASSIGN_MINER, UiPayloads.Action.ASSIGN_BLACKSMITH, UiPayloads.Action.ASSIGN_MERCHANT};
            int cw = (w - 8) / 3, i = 0;
            if (p.getBoolean("remove")) {
                addRenderableWidget(new VillageButton("job_remove", Component.translatable("ui.livingkingdoms.citizens.remove"), x, top + 92, w, () -> send(UiPayloads.Action.REMOVE_PROFESSION))).active = !pending;
            } else for (int t = 0; t < types.length; t++) {
                final int index = t;
                var flag = t == 0 ? "assign" : "assign_" + types[t];
                var b = addRenderableWidget(new VillageButton("job_" + types[t], Component.translatable("profession.livingkingdoms." + types[t]),
                        x + (i % 3) * (cw + 4), top + 92 + (i / 3) * 22, cw, () -> send(actions[index])));
                b.active = !pending && p.getBoolean(flag);
                b.setTooltip(Tooltip.create(b.active ? tr("level_effect." + types[t]) : Component.translatable(t == 0 ? "ui.livingkingdoms.citizens.requirement" : "ui.livingkingdoms.citizens." + types[t] + "_requirement")));
                i++;
            }
        } else if (mode == 2) {
            String[] kinds = {"forage", "mine", "explore"};
            UiPayloads.Action[] actions = {UiPayloads.Action.EXPEDITION_FORAGE, UiPayloads.Action.EXPEDITION_MINE, UiPayloads.Action.EXPEDITION_EXPLORE};
            for (int k = 0; k < 3; k++) {
                final int index = k;
                var b = addRenderableWidget(new VillageButton("trip_" + kinds[k], Component.translatable("expedition.livingkingdoms.kind." + kinds[k]), x, top + 92 + k * 22, w, () -> send(actions[index])));
                b.active = !pending && p.getBoolean("can_expedition");
                b.setTooltip(Tooltip.create(Component.translatable("expedition.livingkingdoms.describe." + kinds[k])));
            }
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(g, mouseX, mouseY, delta)) return;
        renderBackdrop(g);
        RpgFrame.panel(g, left, top, panelW, panelH);
        var p = person(); var font = this.font;
        // Portrait card with name plaque.
        RpgFrame.card(g, left + 8, top + 8, 88, panelH - 16);
        g.fill(left + 12, top + 12, left + 92, top + panelH - 32, 0x5532261A);
        if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.getEntity(p.getInt("entity")) instanceof LivingEntity npc)
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, left + 12, top + 14, left + 92, top + panelH - 34, 34, 0.0625F, mouseX, mouseY, npc);
        RpgFrame.header(g, left + 10, top + panelH - 30, 84, 18);
        var name = font.plainSubstrByWidth(p.getString("name"), 78);
        g.drawString(font, name, left + 52 - font.width(name) / 2, top + panelH - 25, 0xFFF4E2B8, true);
        int x = left + 104, w = panelW - 114, y = top + 10;
        // Speech bubble.
        if (data.contains("talk", 10)) {
            var talk = data.getCompound("talk");
            var line = Component.literal("“").append(Component.translatable(talk.getString("key"), talk.getString("player"), talk.getString("kingdom"))).append("”");
            var lines = font.split(line.withStyle(net.minecraft.ChatFormatting.ITALIC), w - 8);
            int h = Math.min(4, lines.size()) * 10 + 8;
            RpgFrame.inset(g, x, y, w, h);
            for (int i = 0; i < Math.min(4, lines.size()); i++) g.drawString(font, lines.get(i), x + 4, y + 4 + i * 10, 0xFF5B3A1E, false);
            y += h + 6;
        }
        if (mode == 0) {
            String profession = p.getString("profession");
            int level = Math.max(1, p.getInt("profession_level")), cap = Math.max(1, p.getInt("level_cap"));
            g.drawString(font, Component.translatable("profession.livingkingdoms." + profession).append(" · ").append(tr("level", level, cap)), x, y, VillageTheme.INK, false);
            y += 11;
            if (p.contains("trait")) for (var l : font.split(tr("trait." + p.getString("trait")), w)) { g.drawString(font, l, x, y, VillageTheme.AVAILABLE, false); y += 10; }
            if (!profession.equals("unassigned") && !profession.equals("mayor")) {
                long floor = p.getLong("xp_floor"), next = Math.max(floor + 1, p.getLong("xp_next")), xp = p.getLong("xp");
                g.fill(x, y, x + w, y + 5, VillageTheme.INSET);
                int fill = level >= cap ? w : (int) (w * Math.clamp((xp - floor) / (double) (next - floor), 0, 1));
                g.fill(x, y, x + fill, y + 5, 0xFF6DA862);
                y += 8;
                var effect = level >= cap ? tr("max_level") : tr("next_level", Math.max(0, next - xp), tr("level_effect." + profession));
                for (var l : font.split(effect, w)) { g.drawString(font, l, x, y, VillageTheme.MUTED, false); y += 10; }
            }
            int bond = p.getInt("bond"); var hearts = new StringBuilder();
            for (int i = 0; i < 5; i++) hearts.append(i < bond / 20 ? '❤' : '♡');
            g.drawString(font, Component.translatable("bond.livingkingdoms.level." + p.getString("bond_level")).append(" "),
                    x, y + 2, VillageTheme.INK, false);
            g.drawString(font, hearts.toString() + " " + bond + "/100", x + 70, y + 2, 0xFFD04848, false);
            y += 14;
            if (p.contains("wish_item")) {
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(p.getString("wish_item")));
                g.renderItem(new ItemStack(item, p.getInt("wish_count")), x, y);
                var text = p.getBoolean("wish_done") ? tr("wish_done") : tr("wish", p.getInt("wish_count"), item.getDescription());
                g.drawString(font, font.plainSubstrByWidth(text.getString(), w - 22), x + 20, y + 5, p.getBoolean("wish_done") ? VillageTheme.SUCCESS : VillageTheme.INK, false);
                y += 20;
            }
            if (p.contains("away_kind")) g.drawString(font, tr("away"), x, y, VillageTheme.MUTED, false);
        } else {
            g.drawString(font, tr(mode == 1 ? "choose_job" : "choose_trip", p.getString("name")), x, top + 78, VillageTheme.INK, false);
        }
        super.render(g, mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        VillageClient.closeSession(data.getUUID("session"));
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"), new UUID(0, 0), UiPayloads.Action.CLOSE));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
}

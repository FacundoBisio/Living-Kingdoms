package dev.livingkingdoms.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Animated "reward claimed" banner: drops in from the top, holds, then fades. Display only; the server
 * has already granted everything listed. Queue is bounded so a burst of claims cannot pile up forever.
 */
public final class RewardBanner {
    private static final int MAX_QUEUE = 4;
    private static final long IN = 260, HOLD = 3200, OUT = 400;
    private record Entry(Component title, List<Component> lines, boolean epic, String kind, int color) {}
    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<>();
    private static Entry current;
    private static long startedAt;
    private RewardBanner() {}

    static void receive(CompoundTag data) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        var registries = client.level.registryAccess();
        Component title = Component.Serializer.fromJsonLenient(data.getString("title"), registries);
        var lines = new ArrayList<Component>();
        if (data.getInt("emeralds") > 0) lines.add(Component.translatable("reward.livingkingdoms.emeralds", data.getInt("emeralds")));
        if (data.getInt("reputation") > 0) lines.add(Component.translatable("reward.livingkingdoms.reputation", data.getInt("reputation")));
        if (data.getInt("experience") > 0) lines.add(Component.translatable("reward.livingkingdoms.experience", data.getInt("experience")));
        var items = data.getList("items", 8);
        for (int i = 0; i < Math.min(6, items.size()); i++) {
            var line = Component.Serializer.fromJsonLenient(items.getString(i), registries);
            if (line != null) lines.add(line);
        }
        if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
        int color = data.contains("rarity") ? dev.livingkingdoms.rpg.domain.LootRarity.fromId(data.getString("rarity")).color() & 0xFFFFFF
                : data.getBoolean("epic") ? 0xE2B24A : 0x6DA862;
        QUEUE.add(new Entry(title == null ? Component.empty() : title, List.copyOf(lines), data.getBoolean("epic"), data.getString("kind"), color));
    }

    public static void render(RenderGuiEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;
        long now = Util.getMillis();
        if (current != null && now - startedAt > IN + HOLD + OUT) current = null;
        if (current == null) { current = QUEUE.pollFirst(); startedAt = now; if (current == null) return; }
        long t = now - startedAt;
        float enter = Math.clamp(t / (float) IN, 0F, 1F);
        enter = 1F - (1F - enter) * (1F - enter);
        float alpha = t > IN + HOLD ? 1F - Math.clamp((t - IN - HOLD) / (float) OUT, 0F, 1F) : 1F;
        if (alpha <= 0.02F) return;
        draw(event.getGuiGraphics(), current, enter, alpha, t);
    }

    private static void draw(GuiGraphics g, Entry entry, float enter, float alpha, long t) {
        var font = Minecraft.getInstance().font;
        int width = 180;
        for (var line : entry.lines()) width = Math.max(width, font.width(line) + 40);
        width = Math.max(width, font.width(entry.title()) + 40);
        width = Math.min(width, g.guiWidth() - 20);
        int height = 30 + entry.lines().size() * 11;
        int x = (g.guiWidth() - width) / 2;
        int y = (int) Mth.lerp(enter, -height - 4, 24);
        int a = (int) (alpha * 235) << 24;
        int accent = entry.color();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(x, y, x + width, y + height, a | 0x151C21);
        g.fill(x, y, x + width, y + 2, a | accent);
        g.fill(x, y + height - 2, x + width, y + height, a | accent);
        if (entry.epic() || entry.kind().equals("chest")) {
            // Moving shimmer along the top edge for trial victories.
            int sweep = (int) ((t / 6) % (width + 40)) - 20;
            g.fill(x + Math.max(0, sweep), y, x + Math.min(width, sweep + 20), y + 2, a | 0xFFF3C2);
        }
        int textAlpha = Math.max(4, (int) (alpha * 255)) << 24;
        g.renderItem(new ItemStack(switch (entry.kind()) { case "chest" -> Items.CHEST; case "rank" -> Items.BELL;
            case "contract" -> Items.WRITABLE_BOOK; case "expedition" -> Items.COMPASS; default -> entry.epic() ? Items.NETHER_STAR : Items.EMERALD; }), x + 8, y + 7);
        var header = Component.translatable(switch (entry.kind()) { case "chest", "rank", "contract", "expedition" -> "reward.livingkingdoms.header_" + entry.kind();
            default -> entry.epic() ? "reward.livingkingdoms.header_epic" : "reward.livingkingdoms.header"; });
        g.drawString(font, header, x + 30, y + 6, textAlpha | accent, false);
        g.drawString(font, font.plainSubstrByWidth(entry.title().getString(), width - 36), x + 30, y + 16, textAlpha | 0xF5F2E9, false);
        int lineY = y + 28;
        for (var line : entry.lines()) {
            g.drawString(font, line, x + 30, lineY, textAlpha | 0xE0D9C9, false);
            lineY += 11;
        }
        g.pose().popPose();
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { QUEUE.clear(); current = null; }
}

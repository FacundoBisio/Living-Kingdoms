package dev.livingkingdoms.client;

import com.mojang.math.Axis;
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
 * RPG reward presentation: a ribbon drops in with the title and currency lines, then each item flips in as a
 * framed card with a rarity glow and its count. Kingdom rank-ups add a centred splash with the unlocked perks.
 * Display only; the server already granted everything shown. The queue is bounded.
 */
public final class RewardBanner {
    private static final int MAX_QUEUE = 4;
    private static final long IN = 260, CARD_STEP = 110, CARD_FLIP = 240, HOLD = 3600, OUT = 450;
    private record Entry(Component title, List<Component> lines, List<ItemStack> stacks, List<Component> notes,
                         boolean epic, String kind, int color) {
        long duration() { return IN + stacks.size() * CARD_STEP + HOLD + OUT; }
    }
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
        var stacks = new ArrayList<ItemStack>();
        var saved = data.getList("stacks", 10);
        for (int i = 0; i < Math.min(8, saved.size()); i++) ItemStack.parse(registries, saved.getCompound(i)).ifPresent(stacks::add);
        var notes = new ArrayList<Component>();
        var savedNotes = data.getList("notes", 8);
        for (int i = 0; i < Math.min(6, savedNotes.size()); i++) notes.add(Component.translatable(savedNotes.getString(i)));
        int color = data.contains("rarity") ? dev.livingkingdoms.rpg.domain.LootRarity.fromId(data.getString("rarity")).color() & 0xFFFFFF
                : data.getBoolean("epic") ? 0xE2B24A : 0x6DA862;
        if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
        QUEUE.add(new Entry(title == null ? Component.empty() : title, List.copyOf(lines), List.copyOf(stacks), List.copyOf(notes),
                data.getBoolean("epic"), data.getString("kind"), color));
    }

    public static void render(RenderGuiEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;
        long now = Util.getMillis();
        if (current != null && now - startedAt > current.duration()) current = null;
        if (current == null) { current = QUEUE.pollFirst(); startedAt = now; if (current == null) return; }
        long t = now - startedAt;
        float enter = ease(Math.clamp(t / (float) IN, 0F, 1F));
        float alpha = t > current.duration() - OUT ? 1F - Math.clamp((t - (current.duration() - OUT)) / (float) OUT, 0F, 1F) : 1F;
        if (alpha <= 0.02F) return;
        var g = event.getGuiGraphics();
        g.pose().pushPose(); g.pose().translate(0, 0, 300);
        if (current.kind().equals("rank")) splash(g, current, t, alpha);
        draw(g, current, enter, alpha, t);
        g.pose().popPose();
    }

    private static float ease(float t) { return 1F - (1F - t) * (1F - t) * (1F - t); }

    private static void draw(GuiGraphics g, Entry entry, float enter, float alpha, long t) {
        var font = Minecraft.getInstance().font;
        int cards = entry.stacks().size(), cardW = 24, cardH = 32, gap = 4;
        int width = Math.max(190, Math.max(font.width(entry.title()) + 46, cards * (cardW + gap) + 24));
        for (var line : entry.lines()) width = Math.max(width, font.width(line) + 40);
        width = Math.min(width, g.guiWidth() - 20);
        int linesH = entry.lines().size() * 10;
        int height = 30 + linesH + (cards > 0 ? cardH + 8 : 0);
        int x = (g.guiWidth() - width) / 2;
        int y = (int) Mth.lerp(enter, -height - 8, 18);
        int a = (int) (alpha * 255);
        // Panel: parchment with frame, and a coloured accent glow along the top for the rarity/kind.
        g.setColor(1F, 1F, 1F, alpha);
        RpgFrame.panel(g, x, y, width, height);
        RpgFrame.header(g, x + 2, y + 2, width - 4, 22);
        g.setColor(1F, 1F, 1F, 1F);
        if (entry.epic() || entry.kind().equals("chest")) {
            int sweep = (int) ((t / 5) % (width + 40)) - 20;
            g.fill(x + Math.max(2, sweep), y + 2, x + Math.min(width - 2, sweep + 24), y + 4, (a << 24) | 0xFFF3C2);
        }
        g.renderItem(new ItemStack(switch (entry.kind()) { case "chest" -> Items.CHEST; case "rank" -> Items.BELL;
            case "contract" -> Items.WRITABLE_BOOK; case "expedition" -> Items.COMPASS; case "event" -> Items.FIREWORK_ROCKET;
            default -> entry.epic() ? Items.NETHER_STAR : Items.EMERALD; }), x + 7, y + 5);
        var header = Component.translatable(switch (entry.kind()) { case "chest", "rank", "contract", "expedition", "event" -> "reward.livingkingdoms.header_" + entry.kind();
            default -> entry.epic() ? "reward.livingkingdoms.header_epic" : "reward.livingkingdoms.header"; });
        int text = Math.max(4, a) << 24;
        g.drawString(font, header, x + 28, y + 6, text | entry.color(), true);
        g.drawString(font, font.plainSubstrByWidth(entry.title().getString(), width - 34), x + 28, y + 15, text | 0xF5F2E9, true);
        int lineY = y + 28;
        for (var line : entry.lines()) { g.drawString(font, line, x + 12, lineY, text | 0x3A2614, false); lineY += 10; }
        // Cards flip in one after another.
        int startX = x + (width - (cards * (cardW + gap) - gap)) / 2, cardY = lineY + 2;
        for (int i = 0; i < cards; i++) {
            long local = t - IN - i * CARD_STEP;
            if (local < 0) continue;
            float flip = ease(Math.clamp(local / (float) CARD_FLIP, 0F, 1F));
            var stack = entry.stacks().get(i);
            Integer rarityColor = stack.getRarity().color().getColor();
            int glow = rarityColor == null ? 0xFFFFFF : rarityColor;
            int cx = startX + i * (cardW + gap) + cardW / 2;
            g.pose().pushPose();
            g.pose().translate(cx, cardY + cardH / 2F, 0);
            g.pose().scale(Math.max(0.05F, flip), 1F, 1F);
            g.pose().translate(-cardW / 2F, -cardH / 2F, 0);
            int pulse = (int) (60 + 40 * Math.sin((t + i * 200) / 180.0));
            g.fill(-2, -2, cardW + 2, cardH + 2, ((int) (pulse * alpha) << 24) | glow);
            g.setColor(1F, 1F, 1F, alpha);
            RpgFrame.card(g, 0, 0, cardW, cardH);
            g.setColor(1F, 1F, 1F, 1F);
            if (flip > 0.5F) {
                g.renderItem(stack, (cardW - 16) / 2, 5);
                String count = stack.getCount() > 1 ? "×" + stack.getCount() : "";
                if (!count.isEmpty()) g.drawString(font, count, (cardW - font.width(count)) / 2, cardH - 11, text | 0x3A2614, false);
            }
            g.pose().popPose();
        }
    }

    /** Centred rank-up splash with rotating rays, the level and the perks it unlocked. */
    private static void splash(GuiGraphics g, Entry entry, long t, float alpha) {
        var font = Minecraft.getInstance().font;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2 - 10;
        float grow = ease(Math.clamp(t / 400F, 0F, 1F));
        int a = (int) (alpha * 200);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(t / 40F));
        for (int i = 0; i < 12; i++) {
            g.pose().mulPose(Axis.ZP.rotationDegrees(30));
            g.fill(-2, 0, 2, (int) (70 * grow), ((a / 3) << 24) | 0xFFD966);
        }
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(1.6F * grow, 1.6F * grow, 1F);
        var title = Component.translatable("reward.livingkingdoms.header_rank");
        g.drawString(font, title, -font.width(title) / 2, -16, ((int) (alpha * 255) << 24) | 0xFFE9A8, true);
        g.drawString(font, entry.title(), -font.width(entry.title()) / 2, -4, ((int) (alpha * 255) << 24) | 0xFFFFFF, true);
        g.pose().popPose();
        int y = cy + 22;
        for (var note : entry.notes()) {
            var line = Component.literal("✦ ").append(note);
            g.drawString(font, line, cx - font.width(line) / 2, y, ((int) (alpha * 255) << 24) | 0xFFE2C07A, true);
            y += 11;
        }
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { QUEUE.clear(); current = null; }
}

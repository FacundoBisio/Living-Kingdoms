package dev.livingkingdoms.client;

import dev.livingkingdoms.chronicle.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.*;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.event.*;
import java.util.*;

/** Bounded native toast flow; rendering remains entirely on the physical client. */
public final class KingdomAlerts {
    private static final Object TOKEN = new Object();
    private static final ChronicleNoticeQueue QUEUE = new ChronicleNoticeQueue();
    private static AlertToast current;
    private KingdomAlerts() {}
    public static void register() {
        ChroniclePayloads.receiver = KingdomAlerts::receive;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(KingdomAlerts::tick); bus.addListener(KingdomAlerts::logout);
    }
    private static void receive(ChroniclePayloads.Notice notice) {
        if (Minecraft.getInstance().player != null) QUEUE.offer(notice);
    }
    public static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) { clear(); return; }
        if (current != null && client.getToasts().getToast(AlertToast.class, TOKEN) != null) return;
        current = null;
        QUEUE.poll().ifPresent(notice -> { current = new AlertToast(notice); client.getToasts().addToast(current); });
    }
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    private static void clear() { QUEUE.clear(); if (current != null) current.closed = true; current = null; }
    /** Also used by the management history view; building/threat labels remain localizable. */
    public static Component describe(String kind, String subject) {
        Component detail = switch (kind) {
            case "construction_completed", "essential_workplace_empty" -> Component.translatable("construction.livingkingdoms.building." + subject);
            case "significant_threat" -> Component.translatable("chronicle.livingkingdoms.threat." + subject);
            default -> Component.literal(subject);
        };
        return Component.translatable("chronicle.livingkingdoms." + kind, detail);
    }
    private static final class AlertToast implements Toast {
        private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/system");
        final ChroniclePayloads.Notice notice;
        List<FormattedCharSequence> lines = List.of();
        int width = 240, height = 64, screenWidth = -1;
        Language language;
        boolean closed;
        AlertToast(ChroniclePayloads.Notice notice) { this.notice = notice; layout(Minecraft.getInstance().getWindow().getGuiScaledWidth()); }
        @Override public Object getToken() { return TOKEN; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int slotCount() { return (height + 31) / 32; }
        private void layout(int availableWidth) {
            width = Math.clamp(availableWidth / 2, 160, 256); var font = Minecraft.getInstance().font;
            var all = new ArrayList<FormattedCharSequence>();
            all.addAll(font.split(Component.literal(notice.kingdomName()), width - 16));
            all.addAll(font.split(describe(notice.event().kind().key(), notice.event().subject()), width - 16));
            lines = List.copyOf(all); height = 12 + Math.min(8, lines.size()) * 11;
            screenWidth = availableWidth; language = Language.getInstance();
        }
        @Override public Visibility render(GuiGraphics graphics, ToastComponent toasts, long elapsed) {
            if (closed) return Visibility.HIDE;
            if (screenWidth != graphics.guiWidth() || language != Language.getInstance()) layout(graphics.guiWidth());
            graphics.blitSprite(BACKGROUND, 0, 0, width, height);
            int pages = Math.max(1, (lines.size() + 7) / 8);
            double duration = Math.max(6000, pages * 3000L) * toasts.getNotificationDisplayTimeMultiplier();
            int page = Math.min(pages - 1, (int) (elapsed / Math.max(1, duration / pages)));
            for (int i = page * 8, y = 6; i < Math.min(lines.size(), (page + 1) * 8); i++, y += 11)
                graphics.drawString(toasts.getMinecraft().font, lines.get(i), 8, y, i == 0 ? 0xFFFFFF00 : 0xFFFFFFFF, false);
            return elapsed < duration ? Visibility.SHOW : Visibility.HIDE;
        }
    }
}

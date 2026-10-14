package dev.livingkingdoms.client.citizenvisual;

import dev.livingkingdoms.citizenvisual.CitizenPresentation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only current activity, using native controls, narration and paginated wrapped text. */
public final class CitizenActivityScreen extends Screen {
    private CitizenPresentation data;
    private List<FormattedCharSequence> lines = List.of();
    private List<Component> details = List.of();
    private int left, top, panelWidth, panelHeight, rows, page;
    private Button back, next;
    public CitizenActivityScreen(CitizenPresentation data) { super(tr("title")); this.data = data; }
    public boolean matches(CitizenPresentation other) { return data.entityUuid().equals(other.entityUuid()); }
    public void update(CitizenPresentation other) { data = other; rebuildWidgets(); }
    @Override public void tick() {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) { onClose(); return; }
        var entity = minecraft.level.getEntity(data.entityId());
        if (entity == null || !entity.isAlive() || !entity.getUUID().equals(data.entityUuid())
                || minecraft.player.distanceToSqr(entity) > 64) onClose();
    }
    private static Component tr(String key, Object... args) { return Component.translatable("citizen.livingkingdoms." + key, args); }
    @Override protected void init() {
        panelWidth = Math.max(80, Math.min(360, width - 12)); panelHeight = Math.max(80, Math.min(248, height - 12));
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 72) / 12);
        details = List.of(Component.literal(data.name()),
                tr("identity", CitizenVisualClient.profession(data.profession()), data.level()),
                tr("profession_level", data.professionLevel(), data.experience()),
                tr("activity", data.travelling() ? tr("travelling") : Component.translatable("work.livingkingdoms." + data.state().name().toLowerCase(Locale.ROOT))),
                tr("assignment", tr(data.active() ? "active" : "inactive")),
                tr("workplace", data.workplace().isEmpty() ? tr("none") : Component.translatable("construction.livingkingdoms.building." + data.workplace())),
                tr("home", tr(data.housed() ? "housed" : "unhoused")));
        var wrapped = new ArrayList<FormattedCharSequence>();
        for (Component line : details) { wrapped.addAll(font.split(line, panelWidth - 28)); wrapped.add(FormattedCharSequence.EMPTY); }
        lines = List.copyOf(wrapped); page = Math.clamp(page, 0, Math.max(0, (lines.size() - 1) / rows));
        int y = top + panelHeight - 28;
        back = addRenderableWidget(Button.builder(Component.translatable("gui.back"), ignored -> move(-1))
                .bounds(left + 8, y, (panelWidth - 32) / 3, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left + 16 + (panelWidth - 32) / 3, y, (panelWidth - 32) / 3, 20).build());
        next = addRenderableWidget(Button.builder(tr("next"), ignored -> move(1))
                .bounds(left + 24 + (panelWidth - 32) * 2 / 3, y, (panelWidth - 32) / 3, 20).build());
        buttons();
    }
    private void buttons() { back.active = page > 0; next.active = (page + 1) * rows < lines.size(); }
    private void move(int direction) { page = Math.clamp(page + direction, 0, Math.max(0, (lines.size() - 1) / rows)); buttons(); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) { move(vertical > 0 ? -1 : 1); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public Component getNarrationMessage() {
        var narration = title.copy(); for (var detail : details) narration.append(". ").append(detail); return narration;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        graphics.fill(0, 0, width, height, 0x990C151B);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF5E4028);
        graphics.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, 0xFFE6D4A8);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0xFFFFFFFF);
        graphics.enableScissor(left + 12, top + 30, left + panelWidth - 12, top + panelHeight - 34);
        for (int i = 0; i < rows && page * rows + i < lines.size(); i++)
            graphics.drawString(font, lines.get(page * rows + i), left + 14, top + 32 + i * 12, 0xFF24180F, false);
        graphics.disableScissor(); super.render(graphics, mouseX, mouseY, partial);
    }
}

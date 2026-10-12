package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import static dev.livingkingdoms.client.VillageTheme.*;

/** Small vanilla-style conversation: the server owns every step and the final name. */
public final class MayorOnboardingScreen extends Screen {
    private CompoundTag data;
    private int left, top, panelWidth, panelHeight, scroll;
    private boolean pending;
    private EditBox name;
    private VillageButton confirm;
    private String draft = "";

    public MayorOnboardingScreen(CompoundTag data) {
        super(tr("title"));
        this.data = data.copy();
    }

    public boolean sameSession(CompoundTag next) {
        return next.hasUUID("session") && data.getUUID("session").equals(next.getUUID("session"));
    }

    public void update(CompoundTag next) {
        boolean changed = !stage().equals(next.getString("onboarding_stage"));
        if (name != null) draft = name.getValue();
        data = next.copy();
        pending = false;
        if (changed) { scroll = 0; draft = ""; }
        rebuildWidgets();
        triggerImmediateNarration(false);
    }

    private String stage() { return data.getString("onboarding_stage"); }

    @Override protected void init() {
        panelWidth = Math.min(400, Math.max(160, width - 12));
        panelHeight = Math.min(238, Math.max(140, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int footer = top + panelHeight - 27;
        int buttonWidth = panelWidth - 104;
        String action = switch (stage()) {
            case "NEEDS" -> "HELP";
            case "RESPONSIBILITIES" -> "ACCEPT";
            case "NAMING" -> "REGISTER";
            default -> "NEXT";
        };
        String label = switch (stage()) {
            case "WELCOME" -> "people";
            case "PEOPLE" -> "needs";
            case "NEEDS" -> "help";
            case "RESPONSIBILITIES" -> "accept";
            case "NAMING" -> "register";
            default -> "continue";
        };
        if (stage().equals("NAMING") && data.getBoolean("kingdom_registered")) label = "join";
        if (stage().equals("NAMING") && !data.getBoolean("kingdom_registered")) {
            name = new EditBox(font, left + 16, footer - 36, panelWidth - 32, 20, tr("name_label"));
            name.setMaxLength(data.contains("kingdom_name_limit") ? data.getInt("kingdom_name_limit") : 32);
            name.setValue(draft);
            name.setHint(tr("name_hint"));
            name.setResponder(value -> { draft = value; if (confirm != null) confirm.active = !pending && !value.isBlank(); });
            addRenderableWidget(name);
        } else name = null;
        confirm = addRenderableWidget(new VillageButton("mayor:" + action, tr(label), left + 8, footer,
                buttonWidth, () -> send(action)).primary());
        confirm.active = !pending && (name == null || !name.getValue().isBlank());
        addRenderableWidget(new VillageButton("mayor:close", tr("later"), left + panelWidth - 88, footer,
                80, this::onClose));
    }

    @Override protected void setInitialFocus() {
        if(name!=null) setInitialFocus(name);
        else super.setInitialFocus();
    }

    private void send(String action) {
        if (pending) return;
        pending = true;
        confirm.active = false;
        PacketDistributor.sendToServer(new UiPayloads.MayorRequest(data.getUUID("session"), action,
                name == null ? "" : name.getValue()));
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.max(0, scroll - (int)Math.signum(vertical) * 22);
        return true;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, WOOD);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + panelHeight - 3, PARCHMENT);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + 31, BLUE);
        graphics.drawString(font, Component.literal(data.getString("name")), left + 12, top + 12, ON_BLUE, false);
        graphics.drawString(font, tr("stage." + stage().toLowerCase(java.util.Locale.ROOT)), left + 12, top + 40, INK, false);
        int contentTop = top + 59;
        int contentBottom = top + panelHeight - (name == null ? data.contains("notice") ? 50 : 35 : 77);
        var lines = font.split(dialogue(), panelWidth - 32);
        int maxScroll = Math.max(0, lines.size() * 11 - Math.max(11, contentBottom - contentTop));
        scroll = Math.min(scroll, maxScroll);
        graphics.enableScissor(left + 12, contentTop, left + panelWidth - 12, contentBottom);
        int y = contentTop - scroll;
        for (var line : lines) { graphics.drawString(font, line, left + 16, y, INK, false); y += 11; }
        graphics.disableScissor();
        if (maxScroll > 0) graphics.drawString(font, Component.literal("↕"), left + panelWidth - 14, contentTop, MUTED, false);
        if (data.contains("notice")) graphics.drawString(font, font.plainSubstrByWidth(
                Component.translatable(data.getString("notice")).getString(), panelWidth - 24), left + 12, top + panelHeight - 38, ERROR, false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override public Component getNarrationMessage() {
        return tr("title").copy().append(". ").append(dialogue());
    }

    private Component dialogue() {
        String suffix = stage().equals("NAMING") && data.getBoolean("kingdom_registered") ? "joining" : stage().toLowerCase(java.util.Locale.ROOT);
        return tr("text." + suffix, data.getString("settlement"));
    }

    @Override public void onClose() {
        VillageClient.closeSession(data.getUUID("session"));
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"), new java.util.UUID(0, 0), UiPayloads.Action.CLOSE));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    private static Component tr(String key, Object... arguments) { return Component.translatable("onboarding.livingkingdoms." + key, arguments); }
}

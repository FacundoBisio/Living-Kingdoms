package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.DialoguePages;
import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

import static dev.livingkingdoms.client.VillageTheme.*;

/** Short, cached native-font pages. Reviewing an earlier stage never rewinds server progress. */
public final class MayorOnboardingScreen extends KingdomScreen {
    private static final List<String> STAGES = List.of("WELCOME", "PEOPLE", "NEEDS", "RESPONSIBILITIES", "NAMING");
    private CompoundTag data;
    private int left, top, panelWidth, panelHeight, bodyTop, pageY, noticeY, viewedStage, page;
    private boolean pending;
    private EditBox name;
    private VillageButton confirm;
    private String draft = "";
    private List<List<FormattedText>> pages = List.of(List.of());
    private List<List<FormattedCharSequence>> visualPages = List.of(List.of());
    private FormattedCharSequence notice;
    private FormattedCharSequence stageHeading, pageCounter, nameCaption;
    private Language language;

    public MayorOnboardingScreen(CompoundTag data) {
        super(tr("title"));
        this.data = data.copy();
        viewedStage = currentStage();
    }

    public boolean sameSession(CompoundTag next) {
        return next.hasUUID("session") && data.getUUID("session").equals(next.getUUID("session"));
    }

    public void update(CompoundTag next) {
        boolean changed = !data.getString("onboarding_stage").equals(next.getString("onboarding_stage"));
        rememberDraft();
        data = next.copy();
        pending = false;
        if (changed) { viewedStage = currentStage(); page = 0; }
        rebuildWidgets();
        triggerImmediateNarration(false);
    }

    private int currentStage() { return Math.max(0, STAGES.indexOf(data.getString("onboarding_stage"))); }
    private String stage() { return STAGES.get(viewedStage); }
    private boolean naming() { return viewedStage == currentStage() && stage().equals("NAMING") && !data.getBoolean("kingdom_registered"); }
    private void rememberDraft() { if (name != null) draft = name.getValue(); }
    public void reloadUi() { rememberDraft(); rebuildWidgets(); }

    @Override protected void init() {
        rememberDraft(); name = null; confirm = null;
        language = Language.getInstance();
        if (!initReadableSize()) return;
        panelWidth = Math.min(420, width - 12);
        panelHeight = Math.min(252, height - 12);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        int footer = top + panelHeight - 27;
        bodyTop = top + 65;
        pageY = footer - (naming() ? 65 : 27);
        noticeY = footer - (naming() ? 52 : 14);
        int capacity = Math.clamp((pageY - 5 - bodyTop) / 12, 1, 5);
        pages = DialoguePages.of(VillageTextFlow.wrap(font.getSplitter(), dialogue(), panelWidth - 32), capacity);
        visualPages = pages.stream().map(lines -> lines.stream().map(language::getVisualOrder).toList()).toList();
        page = Math.clamp(page, 0, pages.size() - 1);
        notice = data.contains("notice") ? tr(naming()?"name_error":"action_error").getVisualOrderText() : null;
        stageHeading=tr("stage."+stage().toLowerCase(java.util.Locale.ROOT)).getVisualOrderText();
        pageCounter=tr("page",page+1,pages.size()).getVisualOrderText();
        nameCaption=tr("name_label").getVisualOrderText();
        confirm = null;
        if (naming()) {
            name = new EditBox(font, left + 16, footer - 28, panelWidth - 32, 20, tr("name_label"));
            name.setMaxLength(data.contains("kingdom_name_limit") ? data.getInt("kingdom_name_limit") : 32);
            name.setValue(draft); name.setHint(tr("name_hint"));
            name.setResponder(value -> { draft = value; updateConfirm(); });
            addRenderableWidget(name);
        } else name = null;
        int buttonWidth = (panelWidth - 32) / 3;
        var back = addRenderableWidget(new VillageButton("mayor:back", tr("back"), left + 8, footer, buttonWidth, this::previousPage));
        back.active = !pending && (page > 0 || viewedStage > 0);
        confirm = addRenderableWidget(new VillageButton("mayor:continue", confirmLabel(), left + 16 + buttonWidth, footer,
                buttonWidth, this::nextPage).primary());
        confirm.setTooltip(Tooltip.create(lastPage() && viewedStage == currentStage() ? tr(longLabel()) : tr("continue")));
        updateConfirm();
        addRenderableWidget(new VillageButton("mayor:close", tr("close"), left + 24 + buttonWidth * 2, footer, buttonWidth, this::onClose));
    }

    private boolean lastPage() { return page == pages.size() - 1; }
    private String action() {
        return switch (stage()) { case "NEEDS" -> "HELP"; case "RESPONSIBILITIES" -> "ACCEPT"; case "NAMING" -> "REGISTER"; default -> "NEXT"; };
    }
    private String longLabel() {
        return switch (stage()) { case "NEEDS" -> "help"; case "RESPONSIBILITIES" -> "accept";
            case "NAMING" -> data.getBoolean("kingdom_registered") ? "join" : "register"; default -> "continue"; };
    }
    private Component confirmLabel() {
        if (!lastPage() || viewedStage < currentStage()) return tr("continue");
        return tr(switch (stage()) { case "NEEDS" -> "help_short"; case "RESPONSIBILITIES" -> "accept_short";
            case "NAMING" -> data.getBoolean("kingdom_registered") ? "join_short" : "register_short"; default -> "continue"; });
    }
    private void updateConfirm() {
        if (confirm != null) confirm.active = !pending && (!naming() || !lastPage() || !draft.isBlank());
    }
    private void previousPage() {
        if (pending) return;
        rememberDraft();
        if (page > 0) page--; else if (viewedStage > 0) { viewedStage--; page = Integer.MAX_VALUE; }
        rebuildWidgets();
        triggerImmediateNarration(false);
    }
    private void nextPage() {
        if (pending) return;
        rememberDraft();
        if (!lastPage()) { page++; rebuildWidgets(); triggerImmediateNarration(false); }
        else if (viewedStage < currentStage()) { viewedStage++; page = 0; rebuildWidgets(); triggerImmediateNarration(false); }
        else {
            pending = true;
            rebuildWidgets();
            PacketDistributor.sendToServer(new UiPayloads.MayorRequest(data.getUUID("session"), action(), draft));
        }
    }
    @Override protected void setInitialFocus() { if (name != null) setInitialFocus(name); else super.setInitialFocus(); }

    @Override public boolean mouseClicked(double x, double y, int button) {
        boolean handled = super.mouseClicked(x, y, button);
        if (getFocused() instanceof VillageButton old && !children().contains(old))
            for (var child : children()) if (child instanceof VillageButton replacement && replacement.active && replacement.key.equals(old.key)) setFocused(replacement);
        return handled;
    }
    @Override public void tick() {
        if (language != Language.getInstance()) { rememberDraft(); rebuildWidgets(); }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(graphics,mouseX,mouseY,delta)) return;
        renderBackdrop(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, WOOD);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + panelHeight - 3, PAPER);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + 31, BLUE);
        graphics.drawString(font, data.getString("name"), left + 16, top + 12, ON_BLUE, false);
        graphics.drawString(font, stageHeading, left + 16, top + 43, INK, false);
        int y = bodyTop;
        for (var line : visualPages.get(page)) { graphics.drawString(font, line, left + 16, y, INK, false); y += 12; }
        graphics.drawString(font, pageCounter, left + 16, pageY, MUTED, false);
        if (naming()) graphics.drawString(font, nameCaption, left + 16, top + panelHeight - 67, INK, false);
        if (notice != null && viewedStage == currentStage())
            graphics.drawString(font, notice, left + 16, noticeY, ERROR, false);
        super.render(graphics, mouseX, mouseY, delta);
    }
    @Override public Component getNarrationMessage() {
        if (!readable()) return Component.translatable("ui.livingkingdoms.window_small");
        var text = tr("title").copy().append(". ").append(tr("stage." + stage().toLowerCase(java.util.Locale.ROOT)));
        for (var line : pages.get(page)) text.append(". ").append(line.getString());
        return text;
    }
    private Component dialogue() {
        String suffix = stage().equals("NAMING") && data.getBoolean("kingdom_registered") ? "joining" : stage().toLowerCase(java.util.Locale.ROOT);
        return tr("text." + suffix, data.getString("settlement"));
    }
    @Override public void onClose() {
        VillageClient.closeSession(data.getUUID("session"));
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"), new UUID(0, 0), UiPayloads.Action.CLOSE));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    private static Component tr(String key, Object... arguments) { return Component.translatable("onboarding.livingkingdoms." + key, arguments); }
}

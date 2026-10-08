package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.UiPayloads;
import dev.livingkingdoms.ui.VillageUiLayout;
import dev.livingkingdoms.ui.QuestBoardState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import org.lwjgl.glfw.GLFW;

import static dev.livingkingdoms.client.VillageTheme.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/** Shared parchment shell, reusable NPC portrait/options, and a scrollable quest reader. */
public final class VillageScreen extends Screen {
    private CompoundTag data;
    private int left, top, panelWidth, panelHeight, listWidth;
    private int section, offset, detailScroll, detailHeight;
    private UUID selected;
    private boolean pending;
    private UiPayloads.Action pendingAction;
    private UUID pendingQuest;
    private String focusKey;
    private int dialogueStart;
    private VillageUiLayout layout;
    private VillageButton scrollUp, scrollDown;

    public VillageScreen(CompoundTag data) { super(tr("board")); this.data = data; }
    public boolean sameSession(CompoundTag next) { return next.hasUUID("session") && data.getUUID("session").equals(next.getUUID("session")); }
    public void update(CompoundTag next) {
        boolean changedScreen = !data.getString("screen").equals(next.getString("screen"));
        data = next;
        if (changedScreen) { detailScroll = 0; focusKey = null; }
        if (pendingQuest != null) selected = pendingQuest;
        // Keep the accepted request visible when it moves from Requests to Active.
        int nextSection = QuestBoardState.afterAction(data, selected, pendingAction, section);
        if (nextSection != section) { section = nextSection; offset = 0; detailScroll = 0; }
        if (data.contains("notice")) detailScroll = 0;
        pending = false;
        pendingAction = null;
        pendingQuest = null;
        rebuildWidgets();
        triggerImmediateNarration(false);
    }

    @Override protected void rebuildWidgets() {
        if (getFocused() instanceof VillageButton focused) focusKey = focused.key;
        super.rebuildWidgets();
        restoreButtonFocus();
    }

    private void restoreButtonFocus() {
        for (var child : children()) {
            if (child instanceof VillageButton button && button.active && button.key.equals(focusKey)) {
                setFocused(button);
                break;
            }
        }
    }

    @Override public boolean mouseClicked(double x, double y, int mouseButton) {
        boolean handled = super.mouseClicked(x, y, mouseButton);
        // Native click dispatch focuses the clicked instance after its callback. A callback
        // may have rebuilt the screen, so transfer that focus to the replacement widget.
        if (getFocused() instanceof VillageButton button && !children().contains(button)) {
            focusKey = button.key;
            clearFocus();
            restoreButtonFocus();
        }
        return handled;
    }

    @Override public Component getNarrationMessage() {
        Component context = board() ? tr("board") : Component.translatable(
                "npc.livingkingdoms." + data.getString("role") + ".name", data.getString("name"));
        var message = context.copy().append(". ").append(data.getString("settlement"));
        var quest = selectedQuest();
        if (board() && quest != null) {
            message.append(". ").append(questTitle(quest)).append(". ").append(state(quest))
                    .append(". ").append(Component.translatable(quest.getString("objective"), Component.translatable(quest.getString("target"))))
                    .append(". ").append(tr("reward_value", quest.getInt("emeralds"), quest.getInt("reward_reputation")));
            var requirements = quest.getList("requirements", 10);
            for (int i = 0; i < requirements.size(); i++) {
                var item = requirements.getCompound(i);
                message.append(". ").append(icon(item.getString("item")).getHoverName())
                        .append(" ").append(tr("progress", item.getInt("count"), item.getInt("required")));
            }
        } else if (!board()) {
            message.append(". ").append(Component.translatable(data.getString("dialogue"), data.getString("settlement"),
                    data.getInt("level"), data.getInt("reputation")));
        }
        if (pending) message.append(". ").append(tr("working"));
        if (data.contains("notice")) message.append(". ").append(Component.translatable(data.getString("notice")));
        return message;
    }
    private boolean board() { return data.getString("screen").equals("board"); }
    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) { return Component.translatable("ui.livingkingdoms." + key, args); }

    @Override protected void init() {
        layout = VillageUiLayout.fit(width, height, board());
        panelWidth = layout.width(); panelHeight = layout.height();
        left = layout.left(); top = layout.top(); listWidth = layout.listWidth();
        if (board()) initBoard(); else initDialogue();
        int scrollX = left + panelWidth - 27;
        scrollUp = button(tr("scroll_up"), scrollX, layout.contentTop(), 20, () -> scrollDetail(-33)).glyph("^");
        scrollDown = button(tr("scroll_down"), scrollX, layout.contentTop() + 23, 20, () -> scrollDetail(33)).glyph("v");
    }

    private VillageButton button(Component label, int x, int y, int w, Runnable action) {
        return button(label.getString(), label, x, y, w, action);
    }

    private VillageButton button(String key, Component label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new VillageButton(key, label, x, y, w, action));
    }

    private void initBoard() {
        int tabWidth = (panelWidth-16)/3;
        String[] labels = {"main", "requests", "active"};
        for (int i=0;i<3;i++) {
            final int tab=i;
            VillageButton button=button(tr(labels[i]),left+8+i*tabWidth,top+32,tabWidth-2,() -> {section=tab;offset=0;selected=null;detailScroll=0;rebuildWidgets();});
            button.selected(section == i);
        }
        var quests=visibleQuests();
        if (selected == null || quests.stream().noneMatch(q -> q.getUUID("id").equals(selected))) selected=quests.isEmpty()?null:quests.getFirst().getUUID("id");
        int rows=layout.rows();
        offset=Math.clamp(offset,0,Math.max(0,quests.size()-rows));
        for(int i=offset;i<Math.min(quests.size(),offset+rows);i++) {
            CompoundTag q=quests.get(i);
            var row = button(q.getUUID("id").toString(), questTitle(q), left+8, layout.contentTop()+(i-offset)*34, listWidth-12,
                    () -> {selected=q.getUUID("id");detailScroll=0;rebuildWidgets();});
            row.selected(q.getUUID("id").equals(selected));
            row.setTooltip(Tooltip.create(questTitle(q).copy().append("\n").append(state(q))));
        }
        button(tr("previous"),left+8,layout.footerTop(),20,()->{offset--;rebuildWidgets();}).glyph("<").active=offset>0;
        button(tr("next"),left+31,layout.footerTop(),20,()->{offset++;rebuildWidgets();}).glyph(">").active=offset+rows<quests.size();
        var refresh = button(tr("refresh"),left+55,layout.footerTop(),listWidth-59,()->send(UiPayloads.Action.REFRESH,null));
        if (layout.compact()) refresh.glyph("R");
        refresh.active=!pending;
        CompoundTag q=selectedQuest();
        int x=left+listWidth+8, available=panelWidth-listWidth-20;
        VillageButton action=button("quest_action",tr(pending?"working":q!=null&&q.getBoolean("accept")?"accept":"claim"),x,layout.footerTop(),available/2-3,()->{
            if(q!=null) send(q.getBoolean("accept")?UiPayloads.Action.ACCEPT:UiPayloads.Action.CLAIM,q.getUUID("id"));
        });
        action.primary();
        action.active=!pending&&q!=null&&(q.getBoolean("accept")||q.getBoolean("claim"));
        button(tr("leave"),x+available/2+3,layout.footerTop(),available/2-3,this::onClose);
    }

    private void initDialogue() {
        int x=layout.detailX(),w=Math.min(128,(layout.detailWidth()-4)/2),y=layout.dialogueActionsTop();
        button(tr("talk"),x,y,w,()->send(UiPayloads.Action.TALK,null)).active=!pending;
        button(tr("open_board"),x+w+4,y,w,()->send(UiPayloads.Action.BOARD,null)).active=!pending;
        button(tr("info"),x,y+24,w,()->send(UiPayloads.Action.INFO,null)).active=!pending;
        button(tr("leave"),x+w+4,y+24,w,this::onClose);
    }

    private void send(UiPayloads.Action action, UUID quest) {
        pending=true;
        pendingAction=action;
        pendingQuest=quest;
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"),quest==null?new UUID(0,0):quest,action));
        rebuildWidgets();
    }
    @Override public void onClose() {
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"),new UUID(0,0),UiPayloads.Action.CLOSE));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }

    private List<CompoundTag> visibleQuests() {
        return QuestBoardState.visible(data, section);
    }
    private CompoundTag selectedQuest() { return visibleQuests().stream().filter(q->q.getUUID("id").equals(selected)).findFirst().orElse(null); }
    private Component questTitle(CompoundTag q) { return Component.translatable("quest.livingkingdoms."+q.getString("template")+".title"); }
    private Component state(CompoundTag q) { return Component.translatable("quest.livingkingdoms.state."+q.getString("state").toLowerCase(java.util.Locale.ROOT)); }

    // Screen.render invokes this before its widgets. Drawing a menu background at
    // that point used to blur and cover the already-rendered parchment and text.
    @Override public void renderBackground(GuiGraphics g,int mouseX,int mouseY,float delta) {}

    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        g.fill(0,0,width,height,0x880C151B);
        g.fill(left-2,top-2,left+panelWidth+2,top+panelHeight+2,WOOD);
        g.fill(left,top,left+panelWidth,top+panelHeight,PARCHMENT);
        g.fill(left+4,top+4,left+panelWidth-4,top+27,BLUE);
        g.drawString(font,font.plainSubstrByWidth(data.getString("settlement"),panelWidth-24),left+11,top+11,ON_BLUE,false);
        Component rep=tr("reputation",data.getInt("reputation"));
        if(font.width(data.getString("settlement"))+font.width(rep)<panelWidth-30) g.drawString(font,rep,left+panelWidth-12-font.width(rep),top+11,GOLD,false);
        if(board()) renderBoard(g); else renderDialogue(g,mouseX,mouseY);
        scrollUp.active = detailScroll > 0;
        scrollDown.active = detailScroll < maxDetailScroll();
        scrollUp.visible = scrollDown.visible = maxDetailScroll() > 0;
        super.render(g,mouseX,mouseY,delta);
    }

    private int paragraph(GuiGraphics g,Component text,int x,int y,int width,int color) {
        for(var line:VillageTextFlow.wrap(font.getSplitter(),text,width)) {
            g.drawString(font,net.minecraft.locale.Language.getInstance().getVisualOrder(line),x,y,color,false);
            y+=VillageTextFlow.LINE_HEIGHT;
        }
        return y+VillageTextFlow.PARAGRAPH_GAP;
    }

    private void renderBoard(GuiGraphics g) {
        int divider=left+listWidth;
        g.fill(divider,top+56,divider+1,layout.contentBottom(),WOOD);
        g.fill(divider+4,top+56,left+panelWidth-5,layout.contentBottom(),PAPER);
        var quests=visibleQuests(); int rows=layout.rows();
        for(int i=offset;i<Math.min(quests.size(),offset+rows);i++) {
            var q=quests.get(i); int y=layout.contentTop()+23+(i-offset)*34;
            g.drawString(font,ellipsize(state(q),listWidth-20),left+11,y,q.getUUID("id").equals(selected)?SUCCESS:MUTED,false);
        }
        int x=layout.detailX(),y=layout.contentTop()-detailScroll,w=layout.detailWidth()-24;
        g.enableScissor(x,layout.contentTop(),x+w,layout.contentBottom());
        var q=selectedQuest();
        if(q==null) {
            int start=y;
            if(data.contains("notice")) y=paragraph(g,Component.translatable(data.getString("notice")),x,y,w,ERROR);
            y=paragraph(g,section==2?tr("empty_active"):section==1?tr("empty_requests"):Component.translatable(data.getString("hint")),x,y,w,INK);
            detailHeight=y-start;
        }
        else {
            int start=y;
            y=paragraph(g,questTitle(q).copy().withStyle(net.minecraft.ChatFormatting.BOLD),x,y,w,INK);
            y=paragraph(g,tr("difficulty",Component.translatable(q.getString("difficulty")),q.getInt("level"))
                    .append(" · ").append(tr(q.getString("category").equals("MAIN")?"main":"requests")),x,y,w,MUTED);
            y=paragraph(g,state(q),x,y,w,q.getBoolean("claim")?SUCCESS:INK);
            if(data.contains("notice")) y=paragraph(g,Component.translatable(data.getString("notice")),x,y,w,ERROR);
            y=paragraph(g,Component.translatable("ui.livingkingdoms.description."+q.getString("template")),x,y,w,INK);
            y=sectionHeader(g,tr("objectives"),x,y,w);
            if(q.getList("requirements",10).isEmpty())
                y=paragraph(g,Component.translatable(q.getString("objective"),Component.translatable(q.getString("target"))),x,y,w,INK);
            if(q.contains("target_x")) y=paragraph(g,Component.translatable("quest.livingkingdoms.party_location",q.getInt("target_x"),q.getInt("target_z")),x,y,w,MUTED);
            var items=q.getList("requirements",10);
            for(int i=0;i<items.size();i++) {
                var item=items.getCompound(i); ItemStack stack=icon(item.getString("item"));
                y=itemRow(g,stack,tr("progress",item.getInt("count"),item.getInt("required")).append(" ").append(stack.getHoverName()),x,y,w);
            }
            y+=3;
            y=sectionHeader(g,tr("rewards"),x,y,w);
            y=itemRow(g,new ItemStack(Items.EMERALD),tr("emerald_reward",q.getInt("emeralds")),x,y,w);
            y=paragraph(g,tr("reputation_reward",q.getInt("reward_reputation")),x+22,y,w-22,SUCCESS);
            if(q.getLong("expires")>=0) y=paragraph(g,tr("expires",(q.getLong("expires")+59)/60),x,y,w,MUTED);

            if(q.getString("state").equals("ACTIVE")&&!q.getBoolean("claim")) y=paragraph(g,tr("pending"),x,y,w,MUTED);
            detailHeight=y-start;
        }
        g.disableScissor();
        detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
        int viewport=detailViewport();
        if(detailHeight>viewport) {
            int track=viewport;
            int thumb=Math.max(12,track*viewport/detailHeight);
            int at=layout.contentTop()+Math.min(track-thumb,detailScroll*(track-thumb)/Math.max(1,detailHeight-viewport));
            g.fill(left+panelWidth-5,at,left+panelWidth-3,at+thumb,0xFF927249);
        }
    }

    private int sectionHeader(GuiGraphics g,Component label,int x,int y,int w) {
        Component heading=label.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        int headingHeight=VillageTextFlow.height(VillageTextFlow.wrap(font.getSplitter(),heading,w-8).size())+6;
        g.fill(x,y,x+w,y+headingHeight,INSET);
        return paragraph(g,heading,x+4,y+3,w-8,INK)+3;
    }

    private int itemRow(GuiGraphics g,ItemStack stack,Component label,int x,int y,int w) {
        g.fill(x,y,x+18,y+18,PARCHMENT);
        g.renderItem(stack,x+1,y+1);
        return Math.max(y+23,paragraph(g,label,x+22,y+4,w-22,INK));
    }

    private static ItemStack icon(String item) {
        return new ItemStack(switch(item) {case "iron_ingot"->Items.IRON_INGOT;case "wheat"->Items.WHEAT;case "logs"->Items.OAK_LOG;default->Items.STONE;});
    }

    private String ellipsize(Component text, int availableWidth) {
        String value = text.getString();
        return font.width(value) <= availableWidth ? value
                : font.plainSubstrByWidth(value, availableWidth-font.width("...")) + "...";
    }

    private void renderDialogue(GuiGraphics g,int mouseX,int mouseY) {
        int x=layout.detailX(),w=layout.detailWidth();
        g.fill(left+9,top+35,left+listWidth,top+panelHeight-12,INSET);
        if(minecraft.level!=null&&minecraft.level.getEntity(data.getInt("entity")) instanceof LivingEntity npc)
            InventoryScreen.renderEntityInInventoryFollowsMouse(g,left+10,top+40,left+listWidth-2,top+panelHeight-20,Math.min(55,(panelHeight-75)/2),0.0625F,mouseX,mouseY,npc);
        dialogueStart=paragraph(g,Component.translatable("npc.livingkingdoms."+data.getString("role")+".name",data.getString("name"))
                .withStyle(net.minecraft.ChatFormatting.BOLD),x,top+37,w-24,INK);
        int y=dialogueStart-detailScroll;
        g.enableScissor(x,dialogueStart,x+w-24,layout.dialogueActionsTop()-8);
        int end=paragraph(g,Component.translatable(data.getString("dialogue"),data.getString("settlement"),data.getInt("level"),data.getInt("reputation")),x,y,w-24,INK);
        end=paragraph(g,tr("dialogue_status",data.getInt("level"),data.getInt("reputation")),x,end+4,w-24,MUTED);
        detailHeight=end-y;
        g.disableScissor();
        detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
    }

    private int detailViewport() {
        return board() ? layout.viewport() : Math.max(1, layout.dialogueActionsTop()-8-dialogueStart);
    }

    private int maxDetailScroll() { return Math.max(0, detailHeight-detailViewport()); }

    private void scrollDetail(int amount) {
        detailScroll=Math.clamp(detailScroll+amount, 0, maxDetailScroll());
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
            scrollDetail((key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(22, detailViewport()-11));
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if (x < left || x > left+panelWidth || y < top+56 || y > top+panelHeight-30)
            return super.mouseScrolled(x,y,horizontal,vertical);
        if (board() && x < left+listWidth) {offset+=(int)-Math.signum(vertical);rebuildWidgets();}
        else scrollDetail(-(int)(vertical*22));
        return true;
    }
}

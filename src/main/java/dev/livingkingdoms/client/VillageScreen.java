package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.UiPayloads;
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

import java.util.ArrayList;
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
    private VillageButton scrollUp, scrollDown;

    public VillageScreen(CompoundTag data) { super(tr("board")); this.data = data; }
    public boolean sameSession(CompoundTag next) { return next.hasUUID("session") && data.getUUID("session").equals(next.getUUID("session")); }
    public void update(CompoundTag next) {
        boolean changedScreen = !data.getString("screen").equals(next.getString("screen"));
        data = next;
        if (changedScreen) { detailScroll = 0; focusKey = null; }
        if (pendingQuest != null) selected = pendingQuest;
        // Keep the accepted request visible when it moves from Requests to Active.
        if (selected != null && !data.contains("notice")) {
            var quests = data.getList("quests", 10);
            for (int i = 0; i < quests.size(); i++) {
                var quest = quests.getCompound(i);
                if (!selected.equals(quest.getUUID("id"))) continue;
                if (pendingAction == UiPayloads.Action.ACCEPT && quest.getString("state").equals("ACTIVE")) {
                    section = 2; offset = 0; detailScroll = 0;
                } else if (pendingAction == UiPayloads.Action.CLAIM && quest.getString("state").equals("COMPLETED")) {
                    section = quest.getString("category").equals("MAIN") ? 0 : 1;
                    offset = 0; detailScroll = 0;
                }
            }
        }
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
    private static Component tr(String key, Object... args) { return Component.translatable("ui.livingkingdoms." + key, args); }

    @Override protected void init() {
        panelWidth = Math.min(460, width - 12); panelHeight = Math.min(292, height - 12);
        left = (width-panelWidth)/2; top = (height-panelHeight)/2; listWidth = Math.max(100, panelWidth/3);
        if (board()) initBoard(); else initDialogue();
        int scrollX = left + panelWidth - 27;
        scrollUp = button(tr("scroll_up"), scrollX, top + 58, 20, () -> scrollDetail(-33)).glyph("^");
        scrollDown = button(tr("scroll_down"), scrollX, top + 81, 20, () -> scrollDetail(33)).glyph("v");
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
        int rows=Math.max(1,(panelHeight-116)/32);
        offset=Math.clamp(offset,0,Math.max(0,quests.size()-rows));
        for(int i=offset;i<Math.min(quests.size(),offset+rows);i++) {
            CompoundTag q=quests.get(i);
            var row = button(q.getUUID("id").toString(), questTitle(q), left+8, top+59+(i-offset)*32, listWidth-12,
                    () -> {selected=q.getUUID("id");detailScroll=0;rebuildWidgets();});
            row.selected(q.getUUID("id").equals(selected));
            row.setTooltip(Tooltip.create(questTitle(q).copy().append("\n").append(state(q))));
        }
        button(tr("previous"),left+8,top+panelHeight-27,20,()->{offset--;rebuildWidgets();}).glyph("<").active=offset>0;
        button(tr("next"),left+31,top+panelHeight-27,20,()->{offset++;rebuildWidgets();}).glyph(">").active=offset+rows<quests.size();
        button(tr("refresh"),left+55,top+panelHeight-27,listWidth-59,()->send(UiPayloads.Action.REFRESH,null)).active=!pending;
        CompoundTag q=selectedQuest();
        int x=left+listWidth+8, available=panelWidth-listWidth-20;
        VillageButton action=button("quest_action",tr(pending?"working":q!=null&&q.getBoolean("accept")?"accept":"claim"),x,top+panelHeight-27,available/2-3,()->{
            if(q!=null) send(q.getBoolean("accept")?UiPayloads.Action.ACCEPT:UiPayloads.Action.CLAIM,q.getUUID("id"));
        });
        action.primary();
        action.active=!pending&&q!=null&&(q.getBoolean("accept")||q.getBoolean("claim"));
        button(tr("leave"),x+available/2+3,top+panelHeight-27,available/2-3,this::onClose);
    }

    private void initDialogue() {
        int x=left+listWidth+12,w=panelWidth-listWidth-24;
        button(tr("talk"),x,top+panelHeight-99,w,()->send(UiPayloads.Action.TALK,null)).active=!pending;
        button(tr("open_board"),x,top+panelHeight-76,w,()->send(UiPayloads.Action.BOARD,null)).active=!pending;
        button(tr("info"),x,top+panelHeight-53,w,()->send(UiPayloads.Action.INFO,null)).active=!pending;
        button(tr("leave"),x,top+panelHeight-30,w,this::onClose);
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
        List<CompoundTag> result=new ArrayList<>(); var list=data.getList("quests",10);
        for(int i=0;i<list.size();i++) {
            var q=list.getCompound(i); String state=q.getString("state");
            if(section==0&&q.getString("category").equals("MAIN") || section==1&&q.getString("category").equals("DYNAMIC")&&!state.equals("ACTIVE") || section==2&&state.equals("ACTIVE")) result.add(q);
        }
        return result;
    }
    private CompoundTag selectedQuest() { return visibleQuests().stream().filter(q->q.getUUID("id").equals(selected)).findFirst().orElse(null); }
    private Component questTitle(CompoundTag q) { return Component.translatable("quest.livingkingdoms."+q.getString("template")+".title"); }
    private Component state(CompoundTag q) { return Component.translatable("quest.livingkingdoms.state."+q.getString("state").toLowerCase(java.util.Locale.ROOT)); }

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
        if (pending) g.drawString(font, tr("working"), board()?left+listWidth+10:left+12,
                top+panelHeight-(board()?45:24), MUTED, false);
        super.render(g,mouseX,mouseY,delta);
    }

    private int paragraph(GuiGraphics g,Component text,int x,int y,int width,int color) {
        for(var line:font.split(text,Math.max(30,width))) {g.drawString(font,line,x,y,color,false);y+=11;}
        return y+5;
    }

    private void renderBoard(GuiGraphics g) {
        int divider=left+listWidth;
        g.fill(divider,top+57,divider+1,top+panelHeight-33,0xFFBCA575);
        var quests=visibleQuests(); int rows=Math.max(1,(panelHeight-116)/32);
        for(int i=offset;i<Math.min(quests.size(),offset+rows);i++) {
            var q=quests.get(i); int y=top+80+(i-offset)*32;
            g.drawString(font,ellipsize(state(q),listWidth-20),left+11,y,q.getUUID("id").equals(selected)?SUCCESS:MUTED,false);
        }
        int x=divider+10,y=top+59-detailScroll,w=panelWidth-listWidth-44;
        g.enableScissor(divider+3,top+56,left+panelWidth-30,top+panelHeight-51);
        var q=selectedQuest();
        if(q==null) { detailHeight=0; paragraph(g,section==2?tr("empty_active"):section==1?tr("empty_requests"):Component.translatable(data.getString("hint")),x,y,w,MUTED); }
        else {
            int start=y;
            y=paragraph(g,questTitle(q).copy().withStyle(net.minecraft.ChatFormatting.BOLD),x,y,w,INK);
            y=paragraph(g,state(q),x,y,w,q.getBoolean("claim")?SUCCESS:MUTED);
            if(data.contains("notice")) y=paragraph(g,Component.translatable(data.getString("notice")),x,y,w,ERROR);
            y=paragraph(g,Component.translatable("ui.livingkingdoms.description."+q.getString("template")),x,y,w,MUTED);
            y=paragraph(g,tr("difficulty",Component.translatable(q.getString("difficulty")),q.getInt("level")),x,y,w,INK);
            y=paragraph(g,Component.translatable(q.getString("objective"),Component.translatable(q.getString("target"))),x,y,w,INK);
            if(q.contains("target_x")) y=paragraph(g,Component.translatable("quest.livingkingdoms.party_location",q.getInt("target_x"),q.getInt("target_z")),x,y,w,MUTED);
            var items=q.getList("requirements",10);
            for(int i=0;i<items.size();i++) {
                var item=items.getCompound(i); ItemStack stack=icon(item.getString("item"));
                g.renderItem(stack,x,y);
                g.drawString(font,tr("progress",item.getInt("count"),item.getInt("required")),x+21,y+4,INK,false);
                y=paragraph(g,stack.getHoverName(),x+21,y+17,w-21,MUTED);
            }
            y+=3;
            y=paragraph(g,tr("rewards"),x,y,w,INK);
            g.renderItem(new ItemStack(Items.EMERALD),x,y);
            y=paragraph(g,tr("reward_value",q.getInt("emeralds"),q.getInt("reward_reputation")),x+21,y+4,w-21,INK)+6;
            if(q.getLong("expires")>=0) y=paragraph(g,tr("expires",(q.getLong("expires")+59)/60),x,y,w,MUTED);

            if(q.getString("state").equals("ACTIVE")&&!q.getBoolean("claim")) y=paragraph(g,tr("pending"),x,y,w,MUTED);
            detailHeight=y-start;
        }
        g.disableScissor();
        int viewport=detailViewport();
        if(detailHeight>viewport) {
            int track=panelHeight-114;
            int thumb=Math.max(12,track*viewport/detailHeight);
            int at=top+58+Math.min(track-thumb,detailScroll*(track-thumb)/Math.max(1,detailHeight-viewport));
            g.fill(left+panelWidth-5,at,left+panelWidth-3,at+thumb,0xFF927249);
        }
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
        int x=left+listWidth+12,w=panelWidth-listWidth-24;
        g.fill(left+9,top+35,left+listWidth,top+panelHeight-12,INSET);
        if(minecraft.level!=null&&minecraft.level.getEntity(data.getInt("entity")) instanceof LivingEntity npc)
            InventoryScreen.renderEntityInInventoryFollowsMouse(g,left+10,top+40,left+listWidth-2,top+panelHeight-20,Math.min(65,(panelHeight-75)/2),0.0625F,mouseX,mouseY,npc);
        dialogueStart=paragraph(g,Component.translatable("npc.livingkingdoms."+data.getString("role")+".name",data.getString("name"))
                .withStyle(net.minecraft.ChatFormatting.BOLD),x,top+37,w-24,INK);
        int y=dialogueStart-detailScroll;
        g.enableScissor(x,dialogueStart,x+w-20,top+panelHeight-104);
        int end=paragraph(g,Component.translatable(data.getString("dialogue"),data.getString("settlement"),data.getInt("level"),data.getInt("reputation")),x,y,w-24,MUTED);
        detailHeight=end-y;
        g.disableScissor();
    }

    private int detailViewport() {
        return board() ? panelHeight-107 : Math.max(1, top+panelHeight-104-dialogueStart);
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

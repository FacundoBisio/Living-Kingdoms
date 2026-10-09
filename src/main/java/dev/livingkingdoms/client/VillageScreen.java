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

/** Shared parchment shell for Mayor dialogue, quests, construction and citizen approval. */
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
    private long constructionTicks;

    public VillageScreen(CompoundTag data) { super(tr("board")); this.data = data; }
    public boolean sameSession(CompoundTag next) { return next.hasUUID("session") && data.getUUID("session").equals(next.getUUID("session")); }
    public void update(CompoundTag next) {
        boolean changedScreen = !data.getString("screen").equals(next.getString("screen"));
        data = next;
        constructionTicks = 0;
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
        Component context = citizens() ? tr("citizens") : immigration() ? tr("immigration") : construction() ? tr("construction") : board() ? tr("board") : Component.translatable(
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
        } else if (citizens()) {
            narrateSettlementMetrics(message);
            if(quest!=null) for(Component detail:citizenDetails(quest)) message.append(". ").append(detail);
            else message.append(". ").append(tr("citizens.empty"));
        } else if (immigration()) {
            narrateSettlementMetrics(message);
            if(quest!=null) message.append(". ").append(questTitle(quest)).append(". ").append(state(quest))
                    .append(". ").append(tr("immigration.future_role",Component.translatable("citizen.livingkingdoms.role."+quest.getString("role"))));
            else message.append(". ").append(tr("immigration.empty"));
            if(!data.getBoolean("immigration_accept")) message.append(". ").append(immigrationRequirement());
        } else if (construction() && quest != null) {
            message.append(". ").append(questTitle(quest)).append(". ").append(state(quest));
            var costs=quest.getList("requirements",10);
            for(int i=0;i<costs.size();i++) { var cost=costs.getCompound(i);
                message.append(". ").append(icon(cost.getString("item")).getHoverName()).append(" ")
                        .append(tr("progress",cost.getInt("count"),cost.getInt("required")));
            }
        } else if (!wide()) {
            if(data.getBoolean("info")) narrateSettlementMetrics(message);
            message.append(". ").append(Component.translatable(data.getString("dialogue"), data.getString("settlement"),
                    data.getInt("level"), data.getInt("reputation")));
            if(!data.getBoolean("info")) narrateSettlementMetrics(message);
        }
        if (pending) message.append(". ").append(tr("working"));
        if (data.contains("notice")) message.append(". ").append(Component.translatable(data.getString("notice")));
        return message;
    }

    private void narrateSettlementMetrics(net.minecraft.network.chat.MutableComponent message) {
        message.append(". ").append(tr("population",data.getInt("population")))
                .append(". ").append(tr("housing",data.getInt("housing_occupied"),data.getInt("housing_total")))
                .append(". ").append(tr("housing_free",data.getInt("housing_free")))
                .append(". ").append(tr("immigration_pending",data.getInt("immigration_pending")))
                .append(". ").append(tr("food",data.getInt("food_stock"),data.getInt("food_capacity")))
                .append(". ").append(tr("security",data.getInt("security")));
    }
    private boolean board() { return data.getString("screen").equals("board"); }
    private boolean construction() { return data.getString("screen").equals("construction"); }
    private boolean immigration() { return data.getString("screen").equals("immigration"); }
    private boolean citizens() { return data.getString("screen").equals("citizens"); }
    private boolean wide() { return board() || construction() || immigration() || citizens(); }
    @Override public void tick() { if(construction()) constructionTicks++; }
    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) { return Component.translatable("ui.livingkingdoms." + key, args); }

    @Override protected void init() {
        layout = VillageUiLayout.fit(width, height, wide());
        panelWidth = layout.width(); panelHeight = layout.height();
        left = layout.left(); top = layout.top(); listWidth = layout.listWidth();
        if (wide()) initBoard(); else initDialogue();
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
        for (int i=0;i<(board()?3:0);i++) {
            final int tab=i;
            VillageButton button=button(tr(labels[i]),left+8+i*tabWidth,top+32,tabWidth-2,() -> {section=tab;offset=0;selected=null;detailScroll=0;rebuildWidgets();});
            button.selected(section == i);
        }
        var quests=visibleQuests();
        if (selected == null || quests.stream().noneMatch(q -> q.getUUID("id").equals(selected))) selected=quests.isEmpty()?null:
                (construction()?quests.stream().filter(q -> !q.getString("state").equals("COMPLETED")).findFirst().orElse(quests.getLast()):quests.getFirst()).getUUID("id");
        int rows=layout.rows();
        offset=Math.clamp(offset,0,Math.max(0,quests.size()-rows));
        for(int i=offset;i<Math.min(quests.size(),offset+rows);i++) {
            CompoundTag q=quests.get(i);
            var row = button(q.getUUID("id").toString(), questTitle(q), left+8, layout.contentTop()+(i-offset)*34, listWidth-12,
                    () -> {selected=q.getUUID("id");detailScroll=0;rebuildWidgets();});
            row.selected(q.getUUID("id").equals(selected));
            row.setTooltip(Tooltip.create(questTitle(q).copy().append("\n").append(state(q))));
        }
        int navWidth=board()?Math.clamp((listWidth-24)/5,16,20):20;
        button(tr(immigration()?"immigration.previous":"previous"),left+8,layout.footerTop(),navWidth,()->{offset--;rebuildWidgets();}).glyph("<").active=offset>0;
        button(tr(immigration()?"immigration.next":"next"),left+10+navWidth,layout.footerTop(),navWidth,()->{offset++;rebuildWidgets();}).glyph(">").active=offset+rows<quests.size();
        var refresh = button(tr("refresh"),left+12+navWidth*2,layout.footerTop(),board()?navWidth:listWidth-59,
                ()->send(UiPayloads.Action.REFRESH,null));
        if (board() || layout.compact()) refresh.glyph("R");
        refresh.active=!pending;
        if(board()) {
            button(tr("construction"),left+14+navWidth*3,layout.footerTop(),navWidth,
                    ()->send(UiPayloads.Action.CONSTRUCTION,null)).glyph("C").active=!pending;
            button(tr("immigration"),left+16+navWidth*4,layout.footerTop(),navWidth,
                    ()->send(UiPayloads.Action.IMMIGRATION,null)).glyph("I").active=!pending;
        }
        CompoundTag q=selectedQuest();
        int x=left+listWidth+8, available=panelWidth-listWidth-20;
        if(citizens()) {
            int actionWidth=(available-8)/3;
            var assign=button("farmer_assign",tr(pending?"working":q!=null && q.getBoolean("remove")?"citizens.remove":"citizens.assign"),x,layout.footerTop(),actionWidth,
                    ()->{if(q!=null) send(q.getBoolean("remove")?UiPayloads.Action.REMOVE_PROFESSION:UiPayloads.Action.ASSIGN_FARMER,q.getUUID("id"));});
            assign.primary(); assign.active=!pending && q!=null && (q.getBoolean("assign") || q.getBoolean("remove"));
            if(!assign.active && !pending) assign.setTooltip(Tooltip.create(tr("citizens.requirement")));
            var guard=button("guard_assign",tr("citizens.assign_guard"),x+actionWidth+4,layout.footerTop(),actionWidth,
                    ()->{if(q!=null) send(UiPayloads.Action.ASSIGN_GUARD,q.getUUID("id"));});
            guard.active=!pending && q!=null && q.getBoolean("assign_guard");
            if(!guard.active && !pending) guard.setTooltip(Tooltip.create(tr("citizens.guard_requirement")));
            button(tr("back"),x+(actionWidth+4)*2,layout.footerTop(),actionWidth,()->send(returnAction(),null)).active=!pending;
            return;
        }
        if(construction() && data.getBoolean("plan") && data.getString("lifecycle").equals("ESTABLISHED")) {
            int actionWidth=(available-8)/3;
            button("plan_house",tr("plan_house"),x,layout.footerTop(),actionWidth,()->send(UiPayloads.Action.PLAN,null)).active=!pending;
            button("plan_farm",tr("plan_farm"),x+actionWidth+4,layout.footerTop(),actionWidth,()->send(UiPayloads.Action.PLAN_FARM,null)).active=!pending;
            button("plan_barracks",tr("plan_barracks"),x+(actionWidth+4)*2,layout.footerTop(),actionWidth,()->send(UiPayloads.Action.PLAN_BARRACKS,null)).active=!pending && data.getBoolean("barracks_plan");
            return;
        }
        if(immigration()) {
            int actionWidth=(available-8)/3;
            var accept=button("citizen_accept",tr(pending?"working":"immigration.accept"),x,layout.footerTop(),actionWidth,
                    ()->{if(q!=null) send(UiPayloads.Action.ACCEPT_CITIZEN,q.getUUID("id"));});
            accept.primary(); accept.active=!pending && q!=null && data.getBoolean("immigration_accept");
            if(!accept.active && !pending && q!=null) accept.setTooltip(Tooltip.create(immigrationRequirement()));
            button("citizen_decline",tr("immigration.decline"),x+actionWidth+4,layout.footerTop(),actionWidth,
                    ()->{if(q!=null) send(UiPayloads.Action.DECLINE_CITIZEN,q.getUUID("id"));}).active=!pending && q!=null;
            button(tr("back"),x+(actionWidth+4)*2,layout.footerTop(),actionWidth,()->send(switch(data.getString("return_action")) {
                case "BOARD" -> UiPayloads.Action.BOARD;
                case "CONSTRUCTION" -> UiPayloads.Action.CONSTRUCTION;
                default -> UiPayloads.Action.INFO;
            },null)).active=!pending;
            return;
        }
        String constructionAction=data.getBoolean("plan")?(data.getString("lifecycle").equals("ESTABLISHED")?"plan_house":"plan")
                :q!=null&&q.getString("state").equals("FAILED")?"retry":"deposit";
        VillageButton action=button("quest_action",tr(pending?"working":construction()?constructionAction:q!=null&&q.getBoolean("accept")?"accept":"claim"),x,layout.footerTop(),available/2-3,()->{
            if(construction()) {
                if(data.getBoolean("plan")) send(UiPayloads.Action.PLAN,null);
                else if(q!=null) send(q.getString("state").equals("FAILED")?UiPayloads.Action.RETRY:UiPayloads.Action.DEPOSIT,q.getUUID("id"));
            } else if(q!=null) send(q.getBoolean("accept")?UiPayloads.Action.ACCEPT:UiPayloads.Action.CLAIM,q.getUUID("id"));
        });
        action.primary();
        action.active=!pending&&(construction()?data.getBoolean("plan") || q!=null&&(q.getString("state").equals("WAITING_FOR_RESOURCES")
                || q.getString("state").equals("FAILED")):q!=null&&(q.getBoolean("accept")||q.getBoolean("claim")));
        button(tr("leave"),x+available/2+3,layout.footerTop(),available/2-3,this::onClose);
    }

    private void initDialogue() {
        int x=layout.detailX(),w=Math.min(128,(layout.detailWidth()-4)/2),y=dialogueActionsTop();
        button(tr("talk"),x,y,w,()->send(UiPayloads.Action.TALK,null)).active=!pending;
        button(tr("open_board"),x+w+4,y,w,()->send(UiPayloads.Action.BOARD,null)).active=!pending;
        button(tr("info"),x,y+24,w,()->send(UiPayloads.Action.INFO,null)).active=!pending;
        button(tr("construction"),x+w+4,y+24,w,()->send(UiPayloads.Action.CONSTRUCTION,null)).active=!pending;
        button(tr("immigration"),x,y+48,w,()->send(UiPayloads.Action.IMMIGRATION,null)).active=!pending;
        button(tr("citizens"),x+w+4,y+48,w,()->send(UiPayloads.Action.CITIZENS,null)).active=!pending;
        button(tr("refresh"),x,y+72,w,()->send(UiPayloads.Action.REFRESH,null)).active=!pending;
        button(tr("leave"),x+w+4,y+72,w,this::onClose);
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

    private UiPayloads.Action returnAction() {
        return switch(data.getString("return_action")) {
            case "BOARD" -> UiPayloads.Action.BOARD;
            case "CONSTRUCTION" -> UiPayloads.Action.CONSTRUCTION;
            default -> UiPayloads.Action.INFO;
        };
    }
    private List<CompoundTag> visibleQuests() {
        if(citizens()) {
            var list=data.getList("citizens",10); var people=new java.util.ArrayList<CompoundTag>();
            for(int i=0;i<list.size();i++) people.add(list.getCompound(i)); return people;
        }
        if(immigration()) {
            var list=data.getList("candidates",10); var candidates=new java.util.ArrayList<CompoundTag>();
            for(int i=0;i<list.size();i++) candidates.add(list.getCompound(i)); return candidates;
        }
        if(construction()) {
            var list=data.getList("projects",10); var projects=new java.util.ArrayList<CompoundTag>();
            for(int i=0;i<list.size();i++) projects.add(list.getCompound(i)); return projects;
        }
        return QuestBoardState.visible(data, section);
    }
    private CompoundTag selectedQuest() { return visibleQuests().stream().filter(q->q.getUUID("id").equals(selected)).findFirst().orElse(null); }
    private Component questTitle(CompoundTag q) { return (immigration() || citizens())?Component.literal(q.getString("name")):Component.translatable(construction()?"construction.livingkingdoms.building."+q.getString("building"):
            "quest.livingkingdoms."+q.getString("template")+".title"); }
    private Component state(CompoundTag q) { return citizens()?tr("citizens.summary",profession(q),q.getInt("profession_level")):immigration()?tr("citizen_level",q.getInt("level")):Component.translatable((construction()?"construction.livingkingdoms.state.":"quest.livingkingdoms.state.")+
            q.getString("state").toLowerCase(java.util.Locale.ROOT)); }

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
        if(citizens()) renderCitizens(g); else if(immigration()) renderImmigration(g); else if(construction()) renderConstruction(g); else if(board()) renderBoard(g); else renderDialogue(g,mouseX,mouseY);
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

    private void renderConstruction(GuiGraphics g) {
        g.drawString(font,ellipsize(tr("construction").append(" · ").append(Component.translatable(
                "construction.livingkingdoms.lifecycle."+data.getString("lifecycle").toLowerCase(java.util.Locale.ROOT))),panelWidth-20),left+10,top+38,INK,false);
        int divider=left+listWidth;
        g.fill(divider,top+56,divider+1,layout.contentBottom(),WOOD);
        g.fill(divider+4,top+56,left+panelWidth-5,layout.contentBottom(),PAPER);
        var projects=visibleQuests();
        for(int i=offset;i<Math.min(projects.size(),offset+layout.rows());i++) {
            var p=projects.get(i); g.drawString(font,ellipsize(state(p),listWidth-20),left+11,layout.contentTop()+23+(i-offset)*34,MUTED,false);
        }
        int x=layout.detailX(),y=layout.contentTop()-detailScroll,w=layout.detailWidth()-24,start=y;
        g.enableScissor(x,layout.contentTop(),x+w,layout.contentBottom());
        var p=selectedQuest();
        if(data.contains("notice")) y=paragraph(g,Component.translatable(data.getString("notice")),x,y,w,ERROR);
        if(p==null) y=paragraph(g,Component.translatable("construction.livingkingdoms.no_project"),x,y,w,INK);
        else {
            y=paragraph(g,questTitle(p).copy().withStyle(net.minecraft.ChatFormatting.BOLD),x,y,w,INK);
            y=paragraph(g,state(p),x,y,w,p.getString("state").equals("COMPLETED")?SUCCESS:INK);
            double progress=p.getDouble("progress");
            if(p.getString("state").equals("BUILDING")) progress=Math.min(1,progress+(double)constructionTicks/Math.max(20,p.getLong("duration")));
            y=paragraph(g,tr("construction_progress",(int)(progress*100)),x,y,w,INK);
            g.fill(x,y,x+w,y+7,INSET); g.fill(x,y,x+(int)(w*progress),y+7,SUCCESS); y+=14;
            if(p.getBoolean("awaiting_chunks")) y=paragraph(g,Component.translatable("construction.livingkingdoms.waiting_chunks"),x,y,w,MUTED);
            else if(p.getBoolean("waiting_site")) y=paragraph(g,Component.translatable("construction.livingkingdoms.waiting_site"),x,y,w,MUTED);
            else if(p.getString("state").equals("BUILDING")) y=paragraph(g,tr("construction_remaining",Math.max(0,p.getLong("remaining")-constructionTicks/20)),x,y,w,MUTED);
            else if(p.getString("state").equals("FAILED")) y=paragraph(g,Component.translatable("construction.livingkingdoms.blocked"),x,y,w,ERROR);
            y=paragraph(g,Component.translatable("construction.livingkingdoms.plot",p.getInt("x"),p.getInt("y"),p.getInt("z")),x,y,w,MUTED);
            var costs=p.getList("requirements",10);
            y=sectionHeader(g,tr("construction_materials"),x,y,w);
            for(int i=0;i<costs.size();i++) {
                var cost=costs.getCompound(i); var stack=icon(cost.getString("item"));
                y=itemRow(g,stack,tr("progress",cost.getInt("count"),cost.getInt("required")).append(" ").append(stack.getHoverName()),x,y,w);
                y=paragraph(g,tr("construction_carried",cost.getInt("carried")),x+22,y,w-22,MUTED);
            }
            if(data.getBoolean("plan")) y=paragraph(g,Component.translatable("construction.livingkingdoms.no_project"),x,y,w,MUTED);
        }
        y=paragraph(g,tr("construction_shared"),x,y,w,MUTED);
        detailHeight=y-start; g.disableScissor(); detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
    }

    private int itemRow(GuiGraphics g,ItemStack stack,Component label,int x,int y,int w) {
        g.fill(x,y,x+18,y+18,PARCHMENT);
        g.renderItem(stack,x+1,y+1);
        return Math.max(y+23,paragraph(g,label,x+22,y+4,w-22,INK));
    }

    private Component immigrationRequirement() {
        if(!data.getBoolean("immigration_enabled")) return tr("immigration.disabled");
        return data.getString("lifecycle").equals("ESTABLISHED") ? tr("immigration.no_housing",data.getInt("immigration_required_housing")) : tr("immigration.founding");
    }

    private void renderImmigration(GuiGraphics g) {
        g.drawString(font,ellipsize(tr("immigration"),panelWidth-20),left+10,top+38,INK,false);
        int divider=left+listWidth;
        g.fill(divider,top+56,divider+1,layout.contentBottom(),WOOD);
        g.fill(divider+4,top+56,left+panelWidth-5,layout.contentBottom(),PAPER);
        var candidates=visibleQuests();
        for(int i=offset;i<Math.min(candidates.size(),offset+layout.rows());i++)
            g.drawString(font,ellipsize(state(candidates.get(i)),listWidth-20),left+11,layout.contentTop()+23+(i-offset)*34,MUTED,false);
        int x=layout.detailX(),y=layout.contentTop()-detailScroll,w=layout.detailWidth()-24,start=y;
        g.enableScissor(x,layout.contentTop(),x+w,layout.contentBottom());
        if(data.contains("notice")) {
            String notice=data.getString("notice");
            y=paragraph(g,Component.translatable(notice),x,y,w,notice.endsWith(".rejected")?ERROR:SUCCESS);
        }
        y=paragraph(g,tr("population",data.getInt("population")),x,y,w,INK);
        y=paragraph(g,tr("housing",data.getInt("housing_occupied"),data.getInt("housing_total")),x,y,w,INK);
        y=paragraph(g,tr("housing_free",data.getInt("housing_free")),x,y,w,MUTED);
        if(!data.getBoolean("immigration_accept")) y=paragraph(g,immigrationRequirement(),x,y,w,ERROR);
        var candidate=selectedQuest();
        if(candidate==null) y=paragraph(g,tr("immigration.empty"),x,y,w,INK);
        else {
            y=sectionHeader(g,questTitle(candidate),x,y,w);
            y=paragraph(g,state(candidate),x,y,w,INK);
            y=paragraph(g,tr("immigration.future_role",Component.translatable("citizen.livingkingdoms.role."+candidate.getString("role"))),x,y,w,INK);
            y=paragraph(g,tr("immigration.role_placeholder"),x,y,w,MUTED);
            y=paragraph(g,tr("immigration.expires",(candidate.getLong("expires")+59)/60),x,y,w,MUTED);
            y=paragraph(g,tr("immigration.accept_help"),x,y,w,INK);
        }
        y=paragraph(g,tr("immigration.shared"),x,y,w,MUTED);
        detailHeight=y-start; g.disableScissor(); detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
    }

    private Component profession(CompoundTag person) {
        return Component.translatable("profession.livingkingdoms."+person.getString("profession"));
    }
    private List<Component> citizenDetails(CompoundTag p) {
        var details=new java.util.ArrayList<Component>();
        details.add(Component.literal(p.getString("name")).withStyle(net.minecraft.ChatFormatting.BOLD));
        details.add(tr("citizen_level",p.getInt("level")));
        details.add(tr("citizens.profession",profession(p),p.getInt("profession_level"),p.getLong("xp")));
        details.add(p.getBoolean("home")?tr("citizens.home",p.getInt("home_x"),p.getInt("home_z")):tr("citizens.no_home"));
        details.add(p.getBoolean("workplace")?tr("citizens.workplace_kind",Component.translatable("construction.livingkingdoms.building."+p.getString("workplace_kind")),p.getInt("work_x"),p.getInt("work_z"),p.getInt("workers"),p.getInt("slots")):tr("citizens.no_workplace"));
        details.add(tr("citizens.status",Component.translatable("citizen.livingkingdoms.state."+p.getString("status")),
                Component.translatable("work.livingkingdoms."+p.getString("work_state")),tr(p.getBoolean("active")?"citizens.active":"citizens.inactive")));
        return details;
    }
    private void renderCitizens(GuiGraphics g) {
        g.drawString(font,ellipsize(tr("citizens"),panelWidth-20),left+10,top+38,INK,false);
        int divider=left+listWidth;
        g.fill(divider,top+56,divider+1,layout.contentBottom(),WOOD);
        g.fill(divider+4,top+56,left+panelWidth-5,layout.contentBottom(),PAPER);
        var people=visibleQuests();
        for(int i=offset;i<Math.min(people.size(),offset+layout.rows());i++)
            g.drawString(font,ellipsize(state(people.get(i)),listWidth-20),left+11,layout.contentTop()+23+(i-offset)*34,MUTED,false);
        int x=layout.detailX(),y=layout.contentTop()-detailScroll,w=layout.detailWidth()-24,start=y;
        g.enableScissor(x,layout.contentTop(),x+w,layout.contentBottom());
        if(data.contains("notice")) y=paragraph(g,Component.translatable(data.getString("notice")),x,y,w,
                data.getString("notice").endsWith("rejected")?ERROR:SUCCESS);
        y=paragraph(g,tr("food",data.getInt("food_stock"),data.getInt("food_capacity")),x,y,w,INK);
        y=paragraph(g,tr("food_produced",data.getLong("food_produced")),x,y,w,MUTED);
        y=paragraph(g,tr("security",data.getInt("security")),x,y,w,INK);
        var person=selectedQuest();
        if(person==null) y=paragraph(g,tr("citizens.empty"),x,y,w,INK);
        else {
            for(Component detail:citizenDetails(person)) y=paragraph(g,detail,x,y,w,INK);
            if(!person.getBoolean("assign") && !person.getBoolean("assign_guard") && !person.getBoolean("remove")) y=paragraph(g,tr("citizens.requirement"),x,y,w,MUTED);
        }
        if(data.getInt("roster_total")>people.size()) y=paragraph(g,tr("citizens.truncated",people.size(),data.getInt("roster_total")),x,y,w,MUTED);
        y=paragraph(g,tr("citizens.shared"),x,y,w,MUTED);
        detailHeight=y-start; g.disableScissor(); detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
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
        g.enableScissor(x,dialogueStart,x+w-24,dialogueActionsTop()-8);
        int end=y;
        if(data.getBoolean("info")) end=renderSettlementMetrics(g,x,end,w-24);
        end=paragraph(g,Component.translatable(data.getString("dialogue"),data.getString("settlement"),data.getInt("level"),data.getInt("reputation")),x,end,w-24,INK);
        end=paragraph(g,tr("dialogue_status",data.getInt("level"),data.getInt("reputation")),x,end+4,w-24,MUTED);
        if(!data.getBoolean("info")) end=renderSettlementMetrics(g,x,end,w-24);
        detailHeight=end-y;
        g.disableScissor();
        detailScroll=Math.clamp(detailScroll,0,maxDetailScroll());
    }

    private int renderSettlementMetrics(GuiGraphics g,int x,int y,int w) {
        y=paragraph(g,tr("population",data.getInt("population")),x,y,w,INK);
        y=paragraph(g,tr("housing",data.getInt("housing_occupied"),data.getInt("housing_total")),x,y,w,INK);
        y=paragraph(g,tr("housing_free",data.getInt("housing_free")),x,y,w,MUTED);
        y=paragraph(g,tr("immigration_pending",data.getInt("immigration_pending")),x,y,w,MUTED);
        y=paragraph(g,tr("food",data.getInt("food_stock"),data.getInt("food_capacity")),x,y,w,INK);
        return paragraph(g,tr("security",data.getInt("security")),x,y,w,INK);
    }

    private int detailViewport() {
        return wide() ? layout.viewport() : Math.max(1, dialogueActionsTop()-8-dialogueStart);
    }

    private int dialogueActionsTop() { return layout.dialogueActionsTop(4); }

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
        if (wide() && x < left+listWidth) {offset+=(int)-Math.signum(vertical);rebuildWidgets();}
        else scrollDetail(-(int)(vertical*22));
        return true;
    }
}

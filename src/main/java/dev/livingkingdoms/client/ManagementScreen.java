package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import static dev.livingkingdoms.client.VillageTheme.*;

/** Paginated local administration: real paid improvements, Builder repair orders and forged guard equipment. */
public final class ManagementScreen extends KingdomScreen {
    private static final UiSessionClosures CLOSED=new UiSessionClosures(64);
    private CompoundTag data;
    private VillageUiLayout layout;
    private List<CompoundTag> rows=List.of();
    private int mode,selected,offset,page;
    private boolean focused;
    private boolean pending;
    private Language language;
    private List<List<FormattedCharSequence>> pages=List.of(List.of());
    private Component caption=Component.empty();
    private CompoundTag cachedData;private int cachedMode=-1,cachedSelected=-1,cachedWidth,cachedLines;private Language cachedLanguage;
    public ManagementScreen(CompoundTag tag) {super(tr("title"));data=tag.copy();}
    private static net.minecraft.network.chat.MutableComponent tr(String key,Object... args) {return Component.translatable("management.livingkingdoms."+key,args);}
    public static void register() {
        ManagementPayloads.receiver=tag->{var client=net.minecraft.client.Minecraft.getInstance();if(client.level==null || client.player==null || !tag.hasUUID("session") || CLOSED.isClosed(tag.getUUID("session"))) return;
            if(tag.getBoolean("closed")) {CLOSED.close(tag.getUUID("session"));if(client.screen instanceof ManagementScreen screen && screen.data.getUUID("session").equals(tag.getUUID("session"))) client.setScreen(null);}
            else if(client.screen instanceof ManagementScreen screen && screen.data.getUUID("session").equals(tag.getUUID("session"))) {screen.data=tag.copy();screen.pending=false;screen.page=0;screen.rebuildWidgets();}
            else client.setScreen(new ManagementScreen(tag));};
    }
    public static void clear() {CLOSED.clear();}
    public void reloadUi() {cachedData=null;rebuildWidgets();}
    @Override protected void init() {
        language=Language.getInstance();if(!initReadableSize()) return;layout=VillageUiLayout.fit(width,height,true);
        String filter=mode==0?"building":"guard";
        rows=(mode==2?data.getCompound("history").getList("entries",10):data.getList("entries",10)).stream().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast)
                .filter(row->mode==2 || row.getString("kind").equals(filter)).toList();
        if(data.contains("tab") && !focused) { focused=true; mode=data.getInt("tab"); rows=List.of(); }
        if(data.hasUUID("focus") && !focused) {
            focused=true;
            for(int i=0;i<rows.size();i++) if(rows.get(i).hasUUID("id") && rows.get(i).getUUID("id").equals(data.getUUID("focus"))) { selected=i; offset=Math.max(0,i-1); }
        }
        selected=Math.clamp(selected,0,Math.max(0,rows.size()-1));offset=Math.clamp(offset,0,Math.max(0,rows.size()-layout.rows()));
        if(mode==3) rows=List.of();
        for(int m=0;m<4;m++) {final int choice=m;var tab=button("tab"+m,tr(new String[]{"buildings","guards","history","walls"}[m]),layout.left()+8+m*32,layout.top()+32,30,()->{mode=choice;selected=offset=page=0;rebuildWidgets();});
            tab.glyph(new String[]{"B","G","H","W"}[m]).selected(mode==m);tab.setTooltip(Tooltip.create(tr(new String[]{"buildings","guards","history","walls"}[m])));}
        button("expand",tr("expand"),layout.left()+layout.width()-112,layout.top()+32,102,()->send(ManagementPayloads.Action.EXPAND)).active=!pending && data.getBoolean("expand");
        for(int i=offset;i<Math.min(rows.size(),offset+layout.rows());i++) {final int index=i;var row=rows.get(i);
            var label=mode==0?Component.translatable(row.getString("name")):Component.literal(mode==2?row.getString("subject"):row.getString("name"));
            button("row"+i,label,layout.left()+8,layout.contentTop()+(i-offset)*34,layout.listWidth()-14,()->{selected=index;page=0;rebuildWidgets();}).selected(i==selected);
        }
        button("previous",Component.translatable("ui.livingkingdoms.previous"),layout.left()+8,layout.footerTop(),20,()->{offset--;rebuildWidgets();}).glyph("<").active=offset>0;
        button("next",Component.translatable("ui.livingkingdoms.next"),layout.left()+30,layout.footerTop(),20,()->{offset++;rebuildWidgets();}).glyph(">").active=offset+layout.rows()<rows.size();
        button("refresh",Component.translatable("ui.livingkingdoms.refresh"),layout.left()+52,layout.footerTop(),layout.listWidth()-58,()->send(ManagementPayloads.Action.REFRESH)).glyph("R").active=!pending;
        var row=selectedRow();preparePages(row);
        int w=(layout.detailWidth()-12)/4,x=layout.detailX();
        if(mode==3) { wallButtons(x,w); pageButtons(); return; }
        var action=button("primary",tr(mode==1?"equip":"upgrade"),x,layout.footerTop(),w,()->send(mode==1?ManagementPayloads.Action.EQUIP:ManagementPayloads.Action.UPGRADE));
        action.active=!pending && row!=null && mode!=2 && row.getBoolean(mode==1?"equip":"upgrade");
        var repair=button("repair",tr(mode==1?"repair_held":"repair"),x+w+4,layout.footerTop(),w,()->send(mode==1?ManagementPayloads.Action.REPAIR_HELD:ManagementPayloads.Action.REPAIR));
        repair.active=!pending && mode!=2 && (mode==1 || row!=null && row.getBoolean("repair"));repair.setTooltip(Tooltip.create(tr(mode==1?"repair_held_help":"repair_help")));
        button("back",Component.translatable("ui.livingkingdoms.back"),x+(w+4)*3,layout.footerTop(),w,()->send(ManagementPayloads.Action.BACK)).active=!pending;
        // Building-specific order: Warehouse deposit, Tavern hire, guard role.
        String kind=row==null?"":row.getString("building_kind");
        if(mode==1 && row!=null) {
            var role=button("special",tr(row.getBoolean("archer")?"make_patrol":"make_archer"),x+(w+4)*2,layout.footerTop(),w,()->send(ManagementPayloads.Action.ARCHER));
            role.active=!pending && row.getBoolean("loaded"); role.setTooltip(Tooltip.create(tr("archer_help")));
        } else if(mode==0 && kind.equals("warehouse")) {
            var deposit=button("special",tr("deposit_all"),x+(w+4)*2,layout.footerTop(),w,()->send(ManagementPayloads.Action.DEPOSIT_ALL));
            deposit.active=!pending; deposit.setTooltip(Tooltip.create(tr("deposit_all_help")));
        } else if(mode==0 && kind.equals("tavern")) {
            var hire=button("special",tr("hire",row.getInt("hire_price")),x+(w+4)*2,layout.footerTop(),w,()->send(ManagementPayloads.Action.HIRE));
            hire.active=!pending; hire.primary(); hire.setTooltip(Tooltip.create(tr("hire_help",row.getInt("hire_price"))));
        }
        pageButtons();
    }
    private void pageButtons() {
        button("page_previous",Component.translatable("ui.livingkingdoms.previous"),layout.left()+layout.width()-54,layout.contentBottom()-20,20,()->{page--;rebuildWidgets();}).glyph("<").active=page>0;
        button("page_next",Component.translatable("ui.livingkingdoms.next"),layout.left()+layout.width()-30,layout.contentBottom()-20,20,()->{page++;rebuildWidgets();}).glyph(">").active=page+1<pages.size();
    }
    private CompoundTag selectedRow() {return rows.isEmpty()?null:rows.get(selected);}
    /** Walls tab: raise/upgrade the ring, choose 1–4 gates, corner towers and gatehouses. */
    private void wallButtons(int x,int w) {
        var wall=data.getCompound("wall");int tier=wall.getInt("tier");
        boolean move=wall.getBoolean("move"); // a tier-3 wall left inside a grown border is moved by the same order
        var fortify=button("fortify",tr(tier>=3?move?"fortify_move":"fortify_max":"fortify",Component.translatable("wall.livingkingdoms.tier."+Math.min(3,tier+1))),x,layout.footerTop(),w,()->send(ManagementPayloads.Action.FORTIFY));
        fortify.active=!pending && !wall.contains("blocked") && (tier<3 || move); if(fortify.active) fortify.primary();
        fortify.setTooltip(Tooltip.create(costTip(tr("fortify_help"),wall.getList("costs",10),wall.getString("blocked"))));
        var gates=button("wall_gates",tr("wall_gates",wall.getInt("gates")),x+w+4,layout.footerTop(),w,()->send(ManagementPayloads.Action.WALL_GATES));
        gates.active=!pending && !wall.contains("blocked_gates"); gates.setTooltip(Tooltip.create(costTip(tr("wall_gates_help"),new ListTag(),wall.getString("blocked_gates"))));
        var towers=button("wall_towers",tr(wall.getBoolean("towers")?"wall_towers_done":"wall_towers"),x+(w+4)*2,layout.footerTop(),w,()->send(ManagementPayloads.Action.WALL_TOWERS));
        towers.active=!pending && !wall.contains("blocked_towers"); towers.setTooltip(Tooltip.create(costTip(tr("wall_towers_help"),wall.getList("tower_costs",10),wall.getString("blocked_towers"))));
        var gatehouse=button("wall_gatehouses",tr(wall.getBoolean("gatehouses")?"wall_gatehouses_done":"wall_gatehouses"),x+(w+4)*3,layout.footerTop(),w,()->send(ManagementPayloads.Action.WALL_GATEHOUSES));
        gatehouse.active=!pending && !wall.contains("blocked_gatehouses"); gatehouse.setTooltip(Tooltip.create(costTip(tr("wall_gatehouses_help"),wall.getList("gatehouse_costs",10),wall.getString("blocked_gatehouses"))));
        // The list column is empty on this tab. Showing closes the screen so the player can look, like the construction preview.
        var preview=button("walls_preview",tr(WallPreviewOverlay.active()?"walls_preview_hide":"walls_preview"),layout.left()+8,layout.contentTop(),layout.listWidth()-14,()->{
            if(WallPreviewOverlay.active()) {WallPreviewOverlay.clear();rebuildWidgets();} else if(WallPreviewOverlay.show(data.getCompound("wall"))) onClose();});
        preview.active=wall.contains("cx");preview.setTooltip(Tooltip.create(tr("walls_preview_help")));
    }
    private Component costTip(Component head,ListTag costs,String blocked) {
        var tip=Component.empty().append(head);
        for(var cost:costs) {var c=(CompoundTag)cost;tip.append("\n").append(tr("cost_line",Component.translatable(c.getString("resource")),c.getInt("amount"),c.getInt("available"))
                .withStyle(c.getInt("available")>=c.getInt("amount")?net.minecraft.ChatFormatting.GREEN:net.minecraft.ChatFormatting.RED));}
        if(!blocked.isEmpty()) tip.append("\n").append(Component.translatable(blocked).withStyle(net.minecraft.ChatFormatting.GOLD));
        return tip;
    }
    private void preparePages(CompoundTag row) {
        int width=layout.detailWidth(),lines=Math.max(1,(layout.viewport()-32)/11);
        if(cachedData!=data || cachedMode!=mode || cachedSelected!=selected || cachedWidth!=width || cachedLines!=lines || cachedLanguage!=language) {
            var wrapped=new ArrayList<FormattedCharSequence>();
            wrapped.addAll(font.split(Component.literal(data.getString("kingdom_name")).withStyle(net.minecraft.ChatFormatting.BOLD),width));
            if(data.contains("notice")) wrapped.addAll(font.split(Component.translatable(data.getString("notice")),width));
            if(mode==3) {
                var wall=data.getCompound("wall");int tier=wall.getInt("tier"),built=wall.getInt("built"),total=wall.getInt("total");
                wrapped.addAll(font.split(tr("walls_title").withStyle(net.minecraft.ChatFormatting.BOLD),width));
                // Older servers send no column counts: fall back to the plain "being raised".
                wrapped.addAll(font.split(tier==0?tr("walls_none"):tr("walls_state",Component.translatable("wall.livingkingdoms.tier."+tier),
                        wall.getBoolean("complete")?tr("walls_complete"):wall.contains("total")?tr("walls_progress",built,total,dev.livingkingdoms.rpg.domain.Fortification.percent(built,total,false)):tr("walls_rising")),width));
                if(wall.getBoolean("old_ring")) wrapped.addAll(font.split(tr("walls_old_ring"),width));
                wrapped.addAll(font.split(tr("walls_layout",wall.getInt("gates"),tr(wall.getBoolean("towers")?"yes":"no"),tr(wall.getBoolean("gatehouses")?"yes":"no")),width));
                if(wall.getInt("breaches")>0) wrapped.addAll(font.split(tr("fortify_breaches",wall.getInt("breaches")),width));
                var costs=wall.getList("costs",10);
                if(!costs.isEmpty()) {var list=Component.empty();
                    for(int i=0;i<costs.size();i++) {var c=costs.getCompound(i);if(i>0) list.append(", ");
                        list.append(tr("walls_cost_item",c.getInt("amount"),Component.translatable(c.getString("resource")))
                                .withStyle(c.getInt("available")>=c.getInt("amount")?net.minecraft.ChatFormatting.DARK_GREEN:net.minecraft.ChatFormatting.DARK_RED));}
                    wrapped.addAll(font.split(tier>=3?tr("walls_next_move",list):tr("walls_next",Component.translatable("wall.livingkingdoms.tier."+(tier+1)),list),width));}
                if(wall.contains("blocked")) wrapped.addAll(font.split(Component.translatable(wall.getString("blocked")),width));
                wrapped.addAll(font.split(tr("walls_how"),width));
                wrapped.addAll(font.split(tr("walls_help"),width));
                for(int t=1;t<=3;t++) {var step=tr("walls_ladder",t==tier?"»":"·",Component.translatable("wall.livingkingdoms.tier."+t),tr("walls_material."+t));
                    wrapped.addAll(font.split(t==tier?step.withStyle(net.minecraft.ChatFormatting.BOLD):step,width));}
            } else if(row==null) wrapped.addAll(font.split(tr("empty"),width));
            else if(mode==2) {
                wrapped.addAll(font.split(KingdomAlerts.describe(row.getString("kind"),row.getString("subject")),width));
                wrapped.addAll(font.split(tr("time",row.getLong("tick")),width));
            } else {
                wrapped.addAll(font.split(mode==0?Component.translatable(row.getString("name")):Component.literal(row.getString("name")),width));
                wrapped.addAll(font.split(tr("level",row.getInt("level")),width));
                if(mode==0) {
                    wrapped.addAll(font.split(tr("location",row.getInt("x"),row.getInt("z")),width));
                    wrapped.addAll(font.split(row.contains("housing")?Component.translatable("citizen.livingkingdoms.home",row.getInt("housing")):tr("workers",row.getInt("workers"),row.getInt("slots")),width));
                    wrapped.addAll(font.split(Component.translatable(row.getString("benefit")),width));
                    if(row.getBoolean("upgrade")) wrapped.addAll(font.split(tr("upgrade_visual").withStyle(net.minecraft.ChatFormatting.DARK_GREEN),width));
                    for(var cost:row.getList("costs",10)) {var c=(CompoundTag)cost;wrapped.addAll(font.split(tr("cost",Component.translatable(c.getString("resource")),c.getInt("amount"),c.getInt("available")),width));}
                    if(row.getBoolean("repair_pending")) wrapped.addAll(font.split(tr("repair_pending"),width));
                } else wrapped.addAll(font.split(tr("equipment",row.getInt("equipment")),width));
            }
            if(mode!=3) wrapped.addAll(font.split(tr("expand_help"),width));pages=DialoguePages.of(wrapped,lines);
            cachedData=data;cachedMode=mode;cachedSelected=selected;cachedWidth=width;cachedLines=lines;cachedLanguage=language;
        }
        page=Math.clamp(page,0,pages.size()-1);caption=Component.translatable("onboarding.livingkingdoms.page",page+1,pages.size());
    }
    private VillageButton button(String key,Component text,int x,int y,int width,Runnable action) {return addRenderableWidget(new VillageButton(key,text,x,y,width,action));}
    private void send(ManagementPayloads.Action action) {
        if(pending) return;pending=true;var row=selectedRow();PacketDistributor.sendToServer(new ManagementPayloads.Request(data.getUUID("session"),data.getLong("revision"),row!=null && row.hasUUID("id")?row.getUUID("id"):new UUID(0,0),action));rebuildWidgets();
    }
    @Override public void onClose() {CLOSED.close(data.getUUID("session"));PacketDistributor.sendToServer(new ManagementPayloads.Request(data.getUUID("session"),0,new UUID(0,0),ManagementPayloads.Action.CLOSE));super.onClose();}
    @Override public void removed() {CLOSED.close(data.getUUID("session"));PacketDistributor.sendToServer(new ManagementPayloads.Request(data.getUUID("session"),0,new UUID(0,0),ManagementPayloads.Action.CLOSE));super.removed();}
    @Override public boolean isPauseScreen() {return false;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        if(language!=Language.getInstance()) rebuildWidgets();if(renderSizeHint(g,mouseX,mouseY,delta)) return;renderBackdrop(g);
        RpgFrame.panel(g,layout.left(),layout.top(),layout.width(),layout.height());
        RpgFrame.header(g,layout.left()+4,layout.top()+4,layout.width()-8,24);
        g.drawString(font,title,layout.left()+10,layout.top()+12,ON_BLUE,false);
        g.fill(layout.detailX()-4,layout.contentTop()-4,layout.left()+layout.width()-6,layout.contentBottom(),PAPER);
        int y=layout.contentTop();for(var line:pages.get(page)) {g.drawString(font,line,layout.detailX(),y,INK,false);y+=11;}
        g.drawString(font,caption,layout.detailX(),layout.contentBottom()-14,MUTED,false);super.render(g,mouseX,mouseY,delta);
    }
}

package dev.livingkingdoms.client;

import dev.livingkingdoms.ui.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
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
        selected=Math.clamp(selected,0,Math.max(0,rows.size()-1));offset=Math.clamp(offset,0,Math.max(0,rows.size()-layout.rows()));
        for(int m=0;m<3;m++) {final int choice=m;button("tab"+m,tr(new String[]{"buildings","guards","history"}[m]),layout.left()+8+m*40,layout.top()+32,36,()->{mode=choice;selected=offset=page=0;rebuildWidgets();}).glyph(new String[]{"B","G","H"}[m]).selected(mode==m);}
        button("expand",tr("expand"),layout.left()+layout.width()-112,layout.top()+32,102,()->send(ManagementPayloads.Action.EXPAND)).active=!pending && data.getBoolean("expand");
        for(int i=offset;i<Math.min(rows.size(),offset+layout.rows());i++) {final int index=i;var row=rows.get(i);
            var label=mode==0?Component.translatable(row.getString("name")):Component.literal(mode==2?row.getString("subject"):row.getString("name"));
            button("row"+i,label,layout.left()+8,layout.contentTop()+(i-offset)*34,layout.listWidth()-14,()->{selected=index;page=0;rebuildWidgets();}).selected(i==selected);
        }
        button("previous",Component.translatable("ui.livingkingdoms.previous"),layout.left()+8,layout.footerTop(),20,()->{offset--;rebuildWidgets();}).glyph("<").active=offset>0;
        button("next",Component.translatable("ui.livingkingdoms.next"),layout.left()+30,layout.footerTop(),20,()->{offset++;rebuildWidgets();}).glyph(">").active=offset+layout.rows()<rows.size();
        button("refresh",Component.translatable("ui.livingkingdoms.refresh"),layout.left()+52,layout.footerTop(),layout.listWidth()-58,()->send(ManagementPayloads.Action.REFRESH)).glyph("R").active=!pending;
        var row=selectedRow();preparePages(row);
        int w=(layout.detailWidth()-8)/3,x=layout.detailX();
        var action=button("primary",tr(mode==1?"equip":"upgrade"),x,layout.footerTop(),w,()->send(mode==1?ManagementPayloads.Action.EQUIP:ManagementPayloads.Action.UPGRADE));
        action.active=!pending && row!=null && mode!=2 && row.getBoolean(mode==1?"equip":"upgrade");
        var repair=button("repair",tr(mode==1?"repair_held":"repair"),x+w+4,layout.footerTop(),w,()->send(mode==1?ManagementPayloads.Action.REPAIR_HELD:ManagementPayloads.Action.REPAIR));
        repair.active=!pending && mode!=2 && (mode==1 || row!=null && row.getBoolean("repair"));repair.setTooltip(Tooltip.create(tr(mode==1?"repair_held_help":"repair_help")));
        button("back",Component.translatable("ui.livingkingdoms.back"),x+(w+4)*2,layout.footerTop(),w,()->send(ManagementPayloads.Action.BACK)).active=!pending;
        button("page_previous",Component.translatable("ui.livingkingdoms.previous"),layout.left()+layout.width()-54,layout.contentBottom()-20,20,()->{page--;rebuildWidgets();}).glyph("<").active=page>0;
        button("page_next",Component.translatable("ui.livingkingdoms.next"),layout.left()+layout.width()-30,layout.contentBottom()-20,20,()->{page++;rebuildWidgets();}).glyph(">").active=page+1<pages.size();
    }
    private CompoundTag selectedRow() {return rows.isEmpty()?null:rows.get(selected);}
    private void preparePages(CompoundTag row) {
        int width=layout.detailWidth(),lines=Math.max(1,(layout.viewport()-32)/11);
        if(cachedData!=data || cachedMode!=mode || cachedSelected!=selected || cachedWidth!=width || cachedLines!=lines || cachedLanguage!=language) {
            var wrapped=new ArrayList<FormattedCharSequence>();
            wrapped.addAll(font.split(Component.literal(data.getString("kingdom_name")).withStyle(net.minecraft.ChatFormatting.BOLD),width));
            if(data.contains("notice")) wrapped.addAll(font.split(Component.translatable(data.getString("notice")),width));
            if(row==null) wrapped.addAll(font.split(tr("empty"),width));
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
                    for(var cost:row.getList("costs",10)) {var c=(CompoundTag)cost;wrapped.addAll(font.split(tr("cost",Component.translatable(c.getString("resource")),c.getInt("amount"),c.getInt("available")),width));}
                    if(row.getBoolean("repair_pending")) wrapped.addAll(font.split(tr("repair_pending"),width));
                } else wrapped.addAll(font.split(tr("equipment",row.getInt("equipment")),width));
            }
            wrapped.addAll(font.split(tr("expand_help"),width));pages=DialoguePages.of(wrapped,lines);
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
        g.fill(layout.left(),layout.top(),layout.left()+layout.width(),layout.top()+layout.height(),WOOD);
        g.fill(layout.left()+2,layout.top()+2,layout.left()+layout.width()-2,layout.top()+layout.height()-2,PARCHMENT);
        g.fill(layout.left()+4,layout.top()+4,layout.left()+layout.width()-4,layout.top()+28,BLUE);
        g.drawString(font,title,layout.left()+10,layout.top()+12,ON_BLUE,false);
        g.fill(layout.detailX()-4,layout.contentTop()-4,layout.left()+layout.width()-6,layout.contentBottom(),PAPER);
        int y=layout.contentTop();for(var line:pages.get(page)) {g.drawString(font,line,layout.detailX(),y,INK,false);y+=11;}
        g.drawString(font,caption,layout.detailX(),layout.contentBottom()-14,MUTED,false);super.render(g,mouseX,mouseY,delta);
    }
}

package dev.livingkingdoms.client;

import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.ui.DialoguePages;
import dev.livingkingdoms.ui.StockUiLayout;
import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static dev.livingkingdoms.client.VillageTheme.*;

/** Five on-demand ledger rows. Inventory, amounts and permissions always come from the server. */
public final class StockScreen extends KingdomScreen {
    private CompoundTag data;
    private List<CompoundTag> rows=List.of();
    private StockUiLayout layout;
    private int selected,offset,page;
    private boolean pending;
    private Language language;
    private List<List<FormattedCharSequence>> pages=List.of(List.of());
    private Component caption=Component.empty();
    private ItemStack icon=ItemStack.EMPTY;
    private String focusKey;
    private CompoundTag cachedData;
    private int cachedSelected=-1,cachedWidth,cachedLines;
    private Language cachedLanguage;

    public StockScreen(CompoundTag data) {super(tr("title"));this.data=data.copy();}
    private static net.minecraft.network.chat.MutableComponent tr(String key,Object... args) {return Component.translatable("ui.livingkingdoms.stock."+key,args);}
    public boolean sameSession(CompoundTag next) {return data.getUUID("session").equals(next.getUUID("session"));}
    public void update(CompoundTag next) {data=next.copy();pending=false;page=0;rebuildWidgets();triggerImmediateNarration(false);}
    public void reloadUi() {cachedData=null;rebuildWidgets();}

    @Override protected void init() {
        language=Language.getInstance();
        if(!initReadableSize()) return;
        layout=StockUiLayout.fit(width,height);var p=layout.panel();
        rows=data.getList("stocks",10).stream().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast).limit(5).toList();
        selected=Math.clamp(selected,0,Math.max(0,rows.size()-1));offset=Math.clamp(offset,0,Math.max(0,rows.size()-layout.rows()));
        for(int i=offset;i<Math.min(rows.size(),offset+layout.rows());i++) {
            final int index=i;var row=rows.get(i);
            var resource=StockResource.valueOf(row.getString("resource"));
            var button=button("resource_"+i,Component.translatable(resource.key()),p.left()+8,p.contentTop()+(i-offset)*26,p.listWidth()-14,
                    ()->{selected=index;page=0;rebuildWidgets();});
            button.selected(i==selected);button.setTooltip(Tooltip.create(Component.translatable(resource.key()).copy().append("\n").append(tr("balance",row.getInt("balance"),row.getInt("capacity")))));
        }
        button("resource_previous",Component.translatable("ui.livingkingdoms.previous"),p.left()+8,p.footerTop(),20,()->{offset--;rebuildWidgets();}).glyph("<").active=offset>0;
        button("resource_next",Component.translatable("ui.livingkingdoms.next"),p.left()+30,p.footerTop(),20,()->{offset++;rebuildWidgets();}).glyph(">").active=offset+layout.rows()<rows.size();
        button("refresh",Component.translatable("ui.livingkingdoms.refresh"),p.left()+52,p.footerTop(),p.listWidth()-58,()->send(UiPayloads.Action.REFRESH)).glyph("R").active=!pending;
        var row=selectedRow();
        preparePages(row);
        button("page_previous",Component.translatable("ui.livingkingdoms.previous"),p.left()+p.width()-54,p.top()+32,20,()->{page--;rebuildWidgets();}).glyph("<").active=page>0;
        button("page_next",Component.translatable("ui.livingkingdoms.next"),p.left()+p.width()-30,p.top()+32,20,()->{page++;rebuildWidgets();}).glyph(">").active=page+1<pages.size();
        int x=p.detailX(),w=layout.actionWidth();
        var deposit=button("deposit",tr("deposit"),x,p.footerTop(),w,()->send(UiPayloads.Action.STOCK_DEPOSIT));
        deposit.active=!pending && data.getBoolean("can_help") && row!=null && row.getInt("deposit")>0;
        var withdraw=button("withdraw",tr("withdraw"),x+w+4,p.footerTop(),w,()->send(UiPayloads.Action.STOCK_WITHDRAW));
        withdraw.active=!pending && data.getBoolean("can_help") && row!=null && row.getInt("withdraw")>0 && row.getBoolean("inventory_room");
        if(row!=null) {
            deposit.setTooltip(Tooltip.create(tr(row.getInt("deposit")>0?"deposit_help":"deposit_unavailable",row.getInt("deposit"))));
            withdraw.setTooltip(Tooltip.create(tr(row.getInt("withdraw")<1?"withdraw_unavailable":!row.getBoolean("inventory_room")?"inventory_full":"withdraw_help",row.getInt("withdraw"))));
        }
        button("back",Component.translatable("ui.livingkingdoms.back"),x+(w+4)*2,p.footerTop(),w,()->send(UiPayloads.Action.CITIZENS)).active=!pending;
        restoreFocus();
    }
    private CompoundTag selectedRow() {return rows.isEmpty()?null:rows.get(selected);}
    private void preparePages(CompoundTag row) {
        int w=layout.panel().detailWidth();
        if(cachedData!=data || cachedSelected!=selected || cachedWidth!=w || cachedLines!=layout.lines() || cachedLanguage!=language) {
        var lines=new ArrayList<FormattedCharSequence>();
        lines.addAll(font.split(Component.literal(data.getString("settlement")).withStyle(net.minecraft.ChatFormatting.BOLD),w));
        if(data.contains("notice")) lines.addAll(font.split(Component.translatable(data.getString("notice")).withStyle(
                data.getString("notice").endsWith("rejected")?net.minecraft.ChatFormatting.DARK_RED:net.minecraft.ChatFormatting.DARK_GREEN),w));
        if(row!=null) {
            var resource=StockResource.valueOf(row.getString("resource"));icon=new ItemStack(resource.item());
            lines.addAll(font.split(Component.translatable(resource.key()).withStyle(net.minecraft.ChatFormatting.BOLD),w));
            lines.addAll(font.split(tr("balance",row.getInt("balance"),row.getInt("capacity")),w));
            lines.addAll(font.split(tr("carried",row.getInt("carried")),w));
            lines.addAll(font.split(tr("available",row.getInt("deposit"),row.getInt("withdraw")),w));
            if(resource==StockResource.FOOD) {
                lines.addAll(font.split(tr("nutrition",row.getInt("nutrition")),w));
                lines.addAll(font.split(tr("reserved",row.getInt("reserved"),row.getInt("pledged")),w));
            } else if(resource==StockResource.EQUIPMENT) lines.addAll(font.split(tr("equipment"),w));
            else if(resource==StockResource.WOOD) lines.addAll(font.split(tr("wood"),w));
            if(!row.getBoolean("inventory_room") && row.getInt("withdraw")>0) lines.addAll(font.split(tr("inventory_full").withStyle(net.minecraft.ChatFormatting.DARK_RED),w));
        }
        lines.addAll(font.split(tr("shared"),w));
        pages=DialoguePages.of(lines,layout.lines());
        cachedData=data;cachedSelected=selected;cachedWidth=w;cachedLines=layout.lines();cachedLanguage=language;
        }
        page=Math.clamp(page,0,pages.size()-1);
        caption=Component.translatable("onboarding.livingkingdoms.page",page+1,pages.size());
    }
    private VillageButton button(String key,Component title,int x,int y,int width,Runnable action) {
        return addRenderableWidget(new VillageButton(key,title,x,y,width,action));
    }
    @Override protected void rebuildWidgets() {
        if(getFocused() instanceof VillageButton b) focusKey=b.key;
        super.rebuildWidgets();
    }
    private void restoreFocus() {
        for(var child:children()) if(child instanceof VillageButton b && b.active && b.key.equals(focusKey)) {setFocused(b);break;}
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        boolean handled=super.mouseClicked(x,y,button);
        if(getFocused() instanceof VillageButton b && !children().contains(b)) {focusKey=b.key;clearFocus();restoreFocus();}
        return handled;
    }
    private void send(UiPayloads.Action action) {
        if(pending) return;pending=true;
        var row=selectedRow();PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"),row==null?new UUID(0,0):row.getUUID("id"),action));
        rebuildWidgets();
    }
    @Override public void onClose() {
        VillageClient.closeSession(data.getUUID("session"));
        PacketDistributor.sendToServer(new UiPayloads.Request(data.getUUID("session"),new UUID(0,0),UiPayloads.Action.CLOSE));super.onClose();
    }
    @Override public boolean isPauseScreen() {return false;}
    @Override public Component getNarrationMessage() {
        if(!readable()) return Component.translatable("ui.livingkingdoms.window_small");
        var row=selectedRow();return tr("title").copy().append(". ").append(data.getString("settlement"))
                .append(row==null?Component.empty():Component.translatable(StockResource.valueOf(row.getString("resource")).key()).append(". ").append(tr("balance",row.getInt("balance"),row.getInt("capacity"))));
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        if(language!=Language.getInstance()) rebuildWidgets();
        if(renderSizeHint(g,mouseX,mouseY,delta)) return;
        renderBackdrop(g);var p=layout.panel();
        g.fill(p.left(),p.top(),p.left()+p.width(),p.top()+p.height(),WOOD);
        g.fill(p.left()+2,p.top()+2,p.left()+p.width()-2,p.top()+p.height()-2,PARCHMENT);
        g.fill(p.left()+4,p.top()+4,p.left()+p.width()-4,p.top()+28,BLUE);
        g.drawString(font,title,p.left()+10,p.top()+12,ON_BLUE,false);
        g.renderItem(icon,p.detailX(),p.top()+33);
        g.drawString(font,caption,p.detailX()+22,p.top()+38,INK,false);
        g.fill(p.left()+p.listWidth(),p.contentTop()-4,p.left()+p.listWidth()+1,p.contentBottom(),WOOD);
        g.fill(p.detailX()-4,p.contentTop()-4,p.left()+p.width()-6,p.contentBottom(),PAPER);
        int y=p.contentTop();for(var line:pages.get(page)) {g.drawString(font,line,p.detailX(),y,INK,false);y+=11;}
        super.render(g,mouseX,mouseY,delta);
    }
}

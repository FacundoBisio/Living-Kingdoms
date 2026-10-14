package dev.livingkingdoms.client;

import dev.livingkingdoms.commerce.CommercePayloads;
import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.ui.UiSessionClosures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import static dev.livingkingdoms.client.VillageTheme.*;

/** Five server-quoted canonical items, ordinary keyboard/narration widgets and responsive village styling. */
public final class CommerceScreen extends KingdomScreen {
    private static final UiSessionClosures CLOSED=new UiSessionClosures(64);
    private CompoundTag data;
    private List<CompoundTag> rows=List.of();
    private boolean pending;
    private int left,top,panelWidth;
    private List<ItemStack> icons=List.of();
    private List<String> labels=List.of();
    private Component balance,notice;
    private net.minecraft.locale.Language language;
    public CommerceScreen(CompoundTag data){super(tr("title"));this.data=data.copy();}
    private static Component tr(String key,Object... args){return Component.translatable("commerce.livingkingdoms."+key,args);}
    public static void register(){CommercePayloads.clientReceiver=CommerceScreen::receive;}
    public static void clear(){CLOSED.clear();}
    private static void receive(CommercePayloads.Snapshot payload) {
        var mc=Minecraft.getInstance();var next=payload.data();if(!next.hasUUID("session"))return;var session=next.getUUID("session");
        if(mc.player==null || mc.level==null) return;
        if(next.getBoolean("closed")){CLOSED.close(session);if(mc.screen instanceof CommerceScreen screen && screen.data.getUUID("session").equals(session))mc.setScreen(null);return;}
        if(CLOSED.isClosed(session))return;
        if(mc.screen instanceof CommerceScreen screen && screen.data.getUUID("session").equals(session)) {screen.data=next.copy();screen.pending=false;screen.rebuildWidgets();}
        else mc.setScreen(new CommerceScreen(next));
    }
    @Override protected void init() {
        if(!initReadableSize())return;
        panelWidth=Math.min(390,width-16);left=(width-panelWidth)/2;top=(height-196)/2;
        rows=data.getList("offers",10).stream().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast).limit(5).toList();
        language=net.minecraft.locale.Language.getInstance();
        icons=rows.stream().map(row->new ItemStack(StockResource.valueOf(row.getString("resource")).item())).toList();
        labels=rows.stream().map(row->font.plainSubstrByWidth(Component.translatable(StockResource.valueOf(row.getString("resource")).key()).getString(),panelWidth-181)).toList();
        balance=tr("balance",data.getInt("treasury"),Math.max(1,(data.getLong("restock")+1199)/1200));
        notice=data.getString("notice").isEmpty()?Component.empty():tr(data.getString("notice"));
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);var resource=StockResource.valueOf(row.getString("resource"));int y=top+38+i*24;
            var buy=addRenderableWidget(new VillageButton("buy_"+resource,tr("buy",row.getInt("buy")),left+panelWidth-144,y,64,()->send(resource,CommercePayloads.Action.BUY)));
            buy.active=!pending && row.getBoolean("can_buy");buy.setTooltip(Tooltip.create(tr("buy_help",row.getInt("stock"))));
            var sell=addRenderableWidget(new VillageButton("sell_"+resource,tr("sell",row.getInt("sell")),left+panelWidth-76,y,64,()->send(resource,CommercePayloads.Action.SELL)));
            sell.active=!pending && row.getBoolean("can_sell");sell.setTooltip(Tooltip.create(tr("sell_help",row.getInt("demand"))));
        }
        addRenderableWidget(new VillageButton("refresh",Component.translatable("ui.livingkingdoms.refresh"),left+12,top+171,100,()->send(StockResource.FOOD,CommercePayloads.Action.REFRESH))).active=!pending;
        addRenderableWidget(new VillageButton("close",Component.translatable("onboarding.livingkingdoms.close"),left+panelWidth-112,top+171,100,this::onClose));
    }
    private void send(StockResource r,CommercePayloads.Action action){if(pending)return;pending=true;PacketDistributor.sendToServer(new CommercePayloads.Request(data.getUUID("session"),data.getUUID("offer"),r,action));rebuildWidgets();}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        if(language!=net.minecraft.locale.Language.getInstance()) rebuildWidgets();
        if(renderSizeHint(g,mouseX,mouseY,delta))return;
        renderBackdrop(g);g.fill(left,top,left+panelWidth,top+196,WOOD);g.fill(left+3,top+3,left+panelWidth-3,top+193,PARCHMENT);
        g.drawCenteredString(font,title,width/2,top+10,INK);
        g.drawCenteredString(font,balance,width/2,top+24,MUTED);
        for(int i=0;i<rows.size();i++) {
            int y=top+38+i*24;
            g.renderItem(icons.get(i),left+12,y+2);g.drawString(font,labels.get(i),left+33,y+6,INK,false);
        }
        if(!data.getString("notice").isEmpty())g.drawCenteredString(font,notice,width/2,top+158,data.getString("notice").equals("success")?SUCCESS:ERROR);
        super.render(g,mouseX,mouseY,delta);
    }
    @Override public void onClose() {
        CLOSED.close(data.getUUID("session"));PacketDistributor.sendToServer(new CommercePayloads.Request(data.getUUID("session"),data.getUUID("offer"),StockResource.FOOD,CommercePayloads.Action.CLOSE));super.onClose();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed() {CLOSED.close(data.getUUID("session"));PacketDistributor.sendToServer(new CommercePayloads.Request(data.getUUID("session"),data.getUUID("offer"),StockResource.FOOD,CommercePayloads.Action.CLOSE));super.removed();}
}

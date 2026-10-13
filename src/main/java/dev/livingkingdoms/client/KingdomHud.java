package dev.livingkingdoms.client;

import dev.livingkingdoms.config.ClientHudConfig;
import dev.livingkingdoms.ui.DialoguePages;
import dev.livingkingdoms.ui.KingdomHudLayout;
import dev.livingkingdoms.ui.KingdomHudPresentation;
import dev.livingkingdoms.ui.KingdomHudPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import java.util.ArrayList;
import java.util.List;

/** Independent cards; word wrapping and pagination happen only on snapshot/layout/font changes. */
public final class KingdomHud {
    private static final ResourceLocation ICONS=ResourceLocation.parse("livingkingdoms:textures/gui/hud_icons.png");
    private record Line(FormattedCharSequence text, KingdomHudPresentation.Icon icon, int color) {}
    private record Page(List<Line> lines, FormattedCharSequence caption, int captionWidth) {}
    private record Card(KingdomHudLayout.Card bounds, List<Page> pages) {}
    private static CompoundTag data=new CompoundTag();
    private static Card kingdom,quest;
    private static boolean dirty=true,expanded,showKingdom,showQuest;
    private static int bossBarBottom,cachedBoss=-1,cachedW=-1,cachedH=-1,cachedX,cachedY,cachedWidth;
    private static long visibleTicks;
    private static Language language;
    private KingdomHud() {}

    static void update(CompoundTag next) {
        var previous=data.getCompound("quest");var incoming=next.getCompound("quest");
        if(previous.hasUUID("id")!=incoming.hasUUID("id") || previous.hasUUID("id") && !previous.getUUID("id").equals(incoming.getUUID("id"))) visibleTicks=0;
        data=next.copy();dirty=true;
    }
    static void invalidateLayout() { dirty=true; }
    public static void clear() { if(!data.isEmpty()) data=new CompoundTag(); kingdom=quest=null; dirty=true; visibleTicks=0; }
    public static void onTick(ClientTickEvent.Post event) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null
                || data.contains("dimension") && !data.getString("dimension").equals(client.level.dimension().location().toString())) clear();
        else if(client.screen==null && !client.isPaused() && ClientHudConfig.HUD_ENABLED.get()) visibleTicks++;
    }
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void onRenderGui(RenderGuiEvent.Pre event) { bossBarBottom=0; }
    public static void onBossProgress(CustomizeGuiOverlayEvent.BossEventProgress event) {
        if(!event.isCanceled()) bossBarBottom=Math.max(bossBarBottom,event.getY()+event.getIncrement());
    }
    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY,ResourceLocation.parse("livingkingdoms:kingdom_quest_hud"),(graphics,delta)->render(graphics));
    }
    private static void render(GuiGraphics graphics) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null || client.options.hideGui || client.screen!=null
                || client.gui.getDebugOverlay().showDebugScreen() || !ClientHudConfig.HUD_ENABLED.get()) return;
        int x=ClientHudConfig.X.get(),y=ClientHudConfig.Y.get(),width=ClientHudConfig.WIDTH.get();
        boolean wide=ClientHudConfig.EXPANDED.get(),local=ClientHudConfig.SHOW_KINGDOM.get(),tracked=ClientHudConfig.SHOW_QUEST.get();
        if(dirty || cachedW!=graphics.guiWidth() || cachedH!=graphics.guiHeight() || cachedBoss!=bossBarBottom
                || cachedX!=x || cachedY!=y || cachedWidth!=width || expanded!=wide || showKingdom!=local || showQuest!=tracked
                || language!=Language.getInstance()) {
            cachedW=graphics.guiWidth(); cachedH=graphics.guiHeight(); cachedBoss=bossBarBottom;
            cachedX=x; cachedY=y; cachedWidth=width; expanded=wide; showKingdom=local; showQuest=tracked;
            language=Language.getInstance(); dirty=false;
            var panels=KingdomHudPresentation.describe(data,wide,local,tracked);
            int actualWidth=Math.min(Math.max(160,width),Math.max(80,cachedW-12));
            var localLines=wrap(panels.kingdom(),actualWidth-28);
            var questLines=wrap(panels.quest(),actualWidth-28);
            var layout=KingdomHudLayout.cards(cachedW,cachedH,bossBarBottom,x,y,width,localLines.size(),questLines.size());
            kingdom=card(layout.kingdom(),localLines); quest=card(layout.quest(),questLines);
        }
        draw(graphics,kingdom); draw(graphics,quest);
    }
    private static List<Line> wrap(List<KingdomHudPresentation.Row> rows,int width) {
        var result=new ArrayList<Line>(); var font=Minecraft.getInstance().font;
        for(var row:rows) {
            boolean first=true;
            for(var line:font.split(row.text(),width)) {
                result.add(new Line(line,first?row.icon():null,KingdomHudPalette.text(row.tone()))); first=false;
            }
        }
        return List.copyOf(result);
    }
    private static Card card(KingdomHudLayout.Card bounds,List<Line> lines) {
        if(bounds==null) return null;
        int capacity=bounds.lineCapacity();
        var content=DialoguePages.of(lines,lines.size()>capacity?Math.max(1,capacity-1):capacity);
        var pages=new ArrayList<Page>(); var font=Minecraft.getInstance().font;
        for(int i=0;i<content.size();i++) {
            var caption=content.size()>1?Component.literal((i+1)+"/"+content.size()):Component.empty();
            pages.add(new Page(content.get(i),caption.getVisualOrderText(),font.width(caption)));
        }
        return new Card(bounds,List.copyOf(pages));
    }
    private static void draw(GuiGraphics graphics,Card card) {
        if(card==null) return;
        var bounds=card.bounds(); var font=Minecraft.getInstance().font;
        int alpha=ClientHudConfig.OPACITY.get();
        graphics.fill(bounds.x(),bounds.y(),bounds.x()+bounds.width(),bounds.y()+bounds.height(),(alpha<<24)|KingdomHudPalette.BACKGROUND);
        graphics.fill(bounds.x(),bounds.y(),bounds.x()+2,bounds.y()+bounds.height(),0xFF8CABB3);
        var page=card.pages().get((int)((visibleTicks/120)%card.pages().size()));
        int y=bounds.y()+6;
        for(var line:page.lines()) {
            if(line.icon()!=null) graphics.blit(ICONS,bounds.x()+6,y,line.icon().ordinal()*8,0,8,8,64,8);
            graphics.drawString(font,line.text(),bounds.x()+20,y,line.color(),false); y+=11;
        }
        if(card.pages().size()>1) graphics.drawString(font,page.caption(),bounds.x()+bounds.width()-6-page.captionWidth(),
                bounds.y()+bounds.height()-14,KingdomHudPalette.text(KingdomHudPresentation.Tone.MUTED),false);
    }
}

package dev.livingkingdoms.client;

import dev.livingkingdoms.config.ClientHudConfig;
import dev.livingkingdoms.ui.KingdomHudLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import static dev.livingkingdoms.client.VillageTheme.*;

/** Minecraft font/theme with a compact parchment strip; rendering never searches world metadata. */
final class KingdomHud {
    private record Line(Component text,int color) {}
    private static CompoundTag data=new CompoundTag();
    private static float opacity;
    private static long previousFrame;
    private KingdomHud() {}
    static void update(CompoundTag next) { data=next.copy(); }
    static void clear() { data=new CompoundTag(); opacity=0; previousFrame=0; }
    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_LEVEL,ResourceLocation.parse("livingkingdoms:kingdom_quest_hud"),(graphics,delta) -> render(graphics));
    }
    private static void render(GuiGraphics g) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.options.hideGui || client.screen!=null) return;
        var lines=new ArrayList<Line>();
        if(data.contains("settlement",10)) {
            var s=data.getCompound("settlement");
            lines.add(new Line(Component.literal(s.getString("name")).withStyle(net.minecraft.ChatFormatting.BOLD),INK));
            lines.add(new Line(tr("reputation",s.getInt("level"),s.getInt("progress"),s.getInt("next")),MUTED));
            lines.add(new Line(tr("population",s.getInt("population"),s.getInt("housing")),MUTED));
        }
        if(data.contains("quest",10)) {
            var q=data.getCompound("quest");
            if(!lines.isEmpty()) lines.add(new Line(Component.empty(),INK));
            lines.add(new Line(tr("active").copy().withStyle(net.minecraft.ChatFormatting.BOLD),INK));
            Component title=q.getString("template").equals("local_defense")?Component.translatable("ui.livingkingdoms.defense.title",q.getString("settlement"))
                    :Component.translatable("quest.livingkingdoms."+q.getString("template")+".title");
            lines.add(new Line(title,q.getBoolean("ready")?SUCCESS:INK));
            lines.add(new Line(objective(q),q.getBoolean("ready")?SUCCESS:q.getBoolean("defense")?ERROR:MUTED));
        }
        long now=net.minecraft.Util.getMillis();
        float elapsed=previousFrame==0?1:Math.min(1,(now-previousFrame)/200f); previousFrame=now;
        float target=ClientHudConfig.HUD_ENABLED.get() && !lines.isEmpty()?1:0;
        opacity=Math.clamp(opacity+(target>opacity?elapsed:-elapsed),0,1);
        if(opacity<.05f || lines.isEmpty()) return;
        int chatHeight=(int)Math.ceil(client.gui.getChat().getHeight()*client.gui.getChat().getScale());
        var layout=KingdomHudLayout.of(g.guiWidth(),g.guiHeight(),chatHeight,lines.size());
        if(!layout.visible()) return;
        g.fill(layout.x(),layout.y(),layout.x()+layout.width(),layout.y()+layout.height(),alpha(HUD_PARCHMENT,opacity));
        g.fill(layout.x(),layout.y(),layout.x()+2,layout.y()+layout.height(),alpha(GOLD,opacity));
        int y=layout.y()+6;
        for(var line:lines) {
            String text=line.text().getString(); int width=layout.width()-14;
            if(client.font.width(text)>width) text=client.font.plainSubstrByWidth(text,Math.max(0,width-client.font.width("...")))+"...";
            g.drawString(client.font,text,layout.x()+7,y,alpha(line.color(),opacity),false); y+=11;
        }
    }
    private static Component objective(CompoundTag q) {
        if(q.getBoolean("defense")) {
            String phase=q.getString("defense_state");
            return tr(phase.equals("approaching") || phase.equals("detected")?"approaching":"remaining",q.getInt("defense_remaining"));
        }
        var requirements=q.getList("requirements",10);
        if(!requirements.isEmpty()) {
            var selected=requirements.getCompound(0);
            for(int i=0;i<requirements.size();i++) { var item=requirements.getCompound(i); if(item.getInt("count")<item.getInt("required")) { selected=item; break; } }
            var item=switch(selected.getString("item")) { case "iron_ingot" -> Items.IRON_INGOT; case "wheat" -> Items.WHEAT; case "logs" -> Items.OAK_LOG; default -> Items.STONE; };
            return tr("resource",selected.getInt("count"),selected.getInt("required"),item.getDescription());
        }
        if(q.contains("party_remaining")) return tr("remaining",q.getInt("party_remaining"));
        return Component.translatable(q.getString("objective"),Component.translatable(q.getString("target")));
    }
    private static Component tr(String key,Object... args) { return Component.translatable("hud.livingkingdoms."+key,args); }
    private static int alpha(int color,float opacity) { return (Math.max(8,(int)((color>>>24)*opacity))<<24)|(color&0xFFFFFF); }
}

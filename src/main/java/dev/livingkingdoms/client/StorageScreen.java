package dev.livingkingdoms.client;

import dev.livingkingdoms.economy.StockResource;
import dev.livingkingdoms.storage.StorageMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import static dev.livingkingdoms.client.VillageTheme.*;

/** Native container input, player inventory and shift-click. Opaque panel deliberately avoids Screen blur. */
public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private java.util.List<net.minecraft.util.FormattedCharSequence> help;
    public StorageScreen(StorageMenu menu,Inventory inventory,Component title) {
        super(menu,inventory,title);imageWidth=230;imageHeight=214;inventoryLabelX=34;inventoryLabelY=116;titleLabelX=10;titleLabelY=8;
    }
    @Override protected void init() {super.init();help=font.split(Component.translatable("storage.livingkingdoms.help"),imageWidth-20);}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float delta) {
        g.fill(0,0,width,height,0x990C151B);renderBg(g,delta,x,y);
    }
    @Override protected void renderBg(GuiGraphics g,float delta,int mouseX,int mouseY) {
        int x=leftPos,y=topPos;g.fill(x,y,x+imageWidth,y+imageHeight,WOOD);g.fill(x+2,y+2,x+imageWidth-2,y+imageHeight-2,PARCHMENT);
        g.fill(x+4,y+4,x+imageWidth-4,y+24,BLUE);
        for(var slot:menu.slots) {g.fill(x+slot.x-1,y+slot.y-1,x+slot.x+17,y+slot.y+17,WOOD);g.fill(x+slot.x,y+slot.y,x+slot.x+16,y+slot.y+16,INSET);}
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY) {
        g.drawString(font,title,titleLabelX,titleLabelY,ON_BLUE,false);
        g.drawString(font,playerInventoryTitle,inventoryLabelX,inventoryLabelY,INK,false);
        for(int i=0;i<StorageMenu.RESOURCES;i++) {
            int value=menu.value(i,0);String label=value>=10000?(value/1000)+"k":Integer.toString(value);
            g.drawString(font,label,34+i*40-font.width(label)/2,50,INK,false);
        }
        int y=66;for(var line:help) {g.drawString(font,line,10,y,INK,false);y+=10;}
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        super.render(g,mouseX,mouseY,delta);
        if(hoveredSlot!=null && hoveredSlot.index<StorageMenu.RESOURCES) {
            int i=hoveredSlot.index;var resource=StockResource.values()[i];
            var text=Component.translatable(resource.key()).append("\n").append(Component.translatable("storage.livingkingdoms.available",menu.value(i,0),menu.value(i,1)))
                    .append("\n").append(Component.translatable("storage.livingkingdoms.construction",menu.value(i,2),menu.value(i,3)));
            if(i==0) text.append("\n").append(Component.translatable("storage.livingkingdoms.food",menu.foodReserve()));
            g.renderTooltip(font,font.split(text,220),mouseX,mouseY);
        } else renderTooltip(g,mouseX,mouseY);
    }
}

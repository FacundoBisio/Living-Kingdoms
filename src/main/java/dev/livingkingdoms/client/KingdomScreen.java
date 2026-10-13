package dev.livingkingdoms.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import dev.livingkingdoms.ui.VillageUiLayout;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;

/** In 1.21.1 Screen.render calls renderBackground again before widgets. Keep that pass inert. */
abstract class KingdomScreen extends Screen {
    private List<FormattedCharSequence> sizeHint = List.of();
    protected KingdomScreen(Component title) { super(title); }

    @Override public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {}

    protected final void renderBackdrop(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, 0x990C151B);
    }
    protected final boolean readable() { return VillageUiLayout.readable(width, height); }
    protected final boolean initReadableSize() {
        if (readable()) return true;
        sizeHint = font.split(Component.translatable("ui.livingkingdoms.window_small"), Math.max(30, width - 24));
        int buttonWidth = Math.min(120, Math.max(40, width - 16));
        addRenderableWidget(new VillageButton("close_small",Component.translatable("onboarding.livingkingdoms.close"),
                (width-buttonWidth)/2, Math.max(0,height-26), buttonWidth, this::onClose));
        return false;
    }
    protected final boolean renderSizeHint(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        if (readable()) return false;
        graphics.fill(0,0,width,height,0xFF15212B);
        int y=12;
        for(var line:sizeHint) { graphics.drawString(font,line,12,y,0xFFF2F4F5,false); y+=11; }
        super.render(graphics,mouseX,mouseY,delta);
        return true;
    }
}

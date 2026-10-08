package dev.livingkingdoms.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Native button input/narration with village colors and a separate selection indicator. */
final class VillageButton extends Button {
    final String key;
    private boolean selected;
    private boolean primary;
    private String glyph;

    VillageButton(String key, Component label, int x, int y, int width, Runnable action) {
        super(x, y, width, 20, label, ignored -> action.run(), DEFAULT_NARRATION);
        this.key = key;
        setTooltip(Tooltip.create(label));
    }

    VillageButton selected(boolean value) { selected = value; return this; }
    VillageButton primary() { primary = true; return this; }
    VillageButton glyph(String value) { glyph = value; return this; }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean accent = active && (selected || primary);
        graphics.fill(x, y, x+w, y+h, VillageTheme.WOOD);
        graphics.fill(x+1, y+1, x+w-1, y+h-1,
                !active ? VillageTheme.DISABLED : accent ? VillageTheme.BLUE
                        : isHoveredOrFocused() ? VillageTheme.HOVER : VillageTheme.INSET);
        if (selected) graphics.fill(x+3, y+4, x+5, y+h-4, VillageTheme.GOLD);
        // Focus is distinct from selection, and does not change the control's bounds.
        if (isHoveredOrFocused() && active) graphics.renderOutline(x-1, y-1, w+2, h+2, VillageTheme.WOOD);
        int textColor = accent ? VillageTheme.ON_BLUE : VillageTheme.INK;
        if (glyph == null) {
            renderScrollingString(graphics, Minecraft.getInstance().font, selected ? 8 : 4, textColor);
        } else {
            var font = Minecraft.getInstance().font;
            graphics.drawString(font, glyph, x+(w-font.width(glyph))/2, y+(h-8)/2, textColor, false);
        }
    }

    @Override protected MutableComponent createNarrationMessage() {
        MutableComponent result = super.createNarrationMessage();
        return selected ? result.append(Component.translatable("ui.livingkingdoms.selected")) : result;
    }
}

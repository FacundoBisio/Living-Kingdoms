package dev.livingkingdoms.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared RPG look for every Living Kingdoms screen: carved wood frame with gold trim and corner rivets,
 * tiled parchment, a royal ribbon header, textured buttons and reward cards. Pure drawing helpers.
 */
final class RpgFrame {
    static final ResourceLocation FRAME = tex("rpg_frame"), PARCHMENT = tex("rpg_parchment"), HEADER = tex("rpg_header"),
            BUTTON = tex("rpg_button"), CARD = tex("rpg_card");
    private static ResourceLocation tex(String name) { return ResourceLocation.fromNamespaceAndPath("livingkingdoms", "textures/gui/" + name + ".png"); }
    private RpgFrame() {}

    /** Framed parchment panel. */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        parchment(g, x + 4, y + 4, w - 8, h - 8);
        nineSlice(g, FRAME, x - 3, y - 3, w + 6, h + 6, 12, 48, 48);
    }

    /** Tiled parchment fill, clipped to the rectangle. */
    static void parchment(GuiGraphics g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.enableScissor(x, y, x + w, y + h);
        for (int ty = y; ty < y + h; ty += 64) for (int tx = x; tx < x + w; tx += 64) g.blit(PARCHMENT, tx, ty, 0, 0, 64, 64, 64, 64);
        g.disableScissor();
    }

    /** Ribbon header across the top of a panel. */
    static void header(GuiGraphics g, int x, int y, int w, int h) { threeSlice(g, HEADER, x, y, w, h, 8, 64, 24); }

    /** Recessed parchment area (lists, detail panes). */
    static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF5E4028);
        g.fill(x, y, x + w, y + h, 0xFFF1E3C3);
        g.fill(x, y, x + w, y + 1, 0x33000000);
    }

    /** state: 0 normal, 1 hover, 2 accent/selected, 3 disabled. */
    static void button(GuiGraphics g, int x, int y, int w, int h, int state) {
        int v = Math.max(0, Math.min(3, state)) * 20;
        int edge = Math.min(4, w / 2);
        g.blit(BUTTON, x, y, edge, h, 0, v, edge, 20, 64, 80);
        g.blit(BUTTON, x + edge, y, w - edge * 2, h, edge, v, 64 - edge * 2, 20, 64, 80);
        g.blit(BUTTON, x + w - edge, y, edge, h, 64 - edge, v, edge, 20, 64, 80);
    }

    static void card(GuiGraphics g, int x, int y, int w, int h) { nineSlice(g, CARD, x, y, w, h, 4, 32, 44); }

    static void nineSlice(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, int c, int tw, int th) {
        int cw = Math.min(c, w / 2), ch = Math.min(c, h / 2), mw = tw - c * 2, mh = th - c * 2;
        g.blit(tex, x, y, cw, ch, 0, 0, cw, ch, tw, th);
        g.blit(tex, x + w - cw, y, cw, ch, tw - cw, 0, cw, ch, tw, th);
        g.blit(tex, x, y + h - ch, cw, ch, 0, th - ch, cw, ch, tw, th);
        g.blit(tex, x + w - cw, y + h - ch, cw, ch, tw - cw, th - ch, cw, ch, tw, th);
        if (w > cw * 2) {
            g.blit(tex, x + cw, y, w - cw * 2, ch, c, 0, mw, ch, tw, th);
            g.blit(tex, x + cw, y + h - ch, w - cw * 2, ch, c, th - ch, mw, ch, tw, th);
        }
        if (h > ch * 2) {
            g.blit(tex, x, y + ch, cw, h - ch * 2, 0, c, cw, mh, tw, th);
            g.blit(tex, x + w - cw, y + ch, cw, h - ch * 2, tw - cw, c, cw, mh, tw, th);
        }
    }

    private static void threeSlice(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, int c, int tw, int th) {
        g.blit(tex, x, y, c, h, 0, 0, c, th, tw, th);
        g.blit(tex, x + c, y, w - c * 2, h, c, 0, tw - c * 2, th, tw, th);
        g.blit(tex, x + w - c, y, c, h, tw - c, 0, c, th, tw, th);
    }
}

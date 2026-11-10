package dev.livingkingdoms.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-down kingdom map. The terrain is painted once on open from the chunks the client already has (vanilla map
 * colours with height shading, never loading anything); buildings, construction sites, residents, walls, the
 * player and the active event are drawn on top from the server snapshot. Hovering shows what is there.
 */
public final class KingdomMapScreen extends KingdomScreen {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "kingdom_map");
    private static final int UNKNOWN = 0xFF2E2620;
    private final CompoundTag data;
    private DynamicTexture texture;
    private int size, left, top, panelW, panelH, mapX, mapY, mapSize;
    private int cx, cz, radius;

    public KingdomMapScreen(CompoundTag data) { super(Component.translatable("map.livingkingdoms.title", data.getString("name"))); this.data = data.copy(); }

    public static void receive(CompoundTag data) { Minecraft.getInstance().setScreen(new KingdomMapScreen(data)); }

    @Override protected void init() {
        if (!initReadableSize()) return;
        cx = data.getInt("x"); cz = data.getInt("z"); radius = Math.max(8, data.getInt("radius"));
        size = radius * 2 + 1;
        panelW = Math.min(width - 16, 460); panelH = Math.min(height - 16, 300);
        left = (width - panelW) / 2; top = (height - panelH) / 2;
        mapSize = Math.min(panelH - 44, panelW - 170);
        mapX = left + 10; mapY = top + 30;
        if (texture == null) paint();
        addRenderableWidget(new VillageButton("map_close", Component.translatable("map.livingkingdoms.close"), left + panelW - 90, top + panelH - 26, 80, this::onClose));
    }

    /** Vanilla map colours for loaded columns; unknown terrain stays parchment-dark. */
    private void paint() {
        var level = Minecraft.getInstance().level;
        var image = new NativeImage(size, size, false);
        int[] previous = new int[size];
        for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++) {
            int x = cx - radius + dx, z = cz - radius + dz;
            int color = UNKNOWN;
            if (level != null && level.hasChunk(x >> 4, z >> 4)) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                var pos = new BlockPos(x, y, z);
                var state = level.getBlockState(pos);
                var map = state.getMapColor(level, pos);
                if (map != MapColor.NONE) {
                    var brightness = dz == 0 ? MapColor.Brightness.NORMAL : y > previous[dx] ? MapColor.Brightness.HIGH : y < previous[dx] ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                    color = map.calculateRGBColor(brightness) | 0xFF000000; // ABGR as NativeImage expects
                }
                previous[dx] = y;
            }
            // Outside the territory circle: dimmed.
            if ((long) (dx - radius) * (dx - radius) + (long) (dz - radius) * (dz - radius) > (long) radius * radius) color = dim(color);
            image.setPixelRGBA(dx, dz, color);
        }
        texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
    }
    private static int dim(int abgr) {
        int a = abgr >>> 24, b = (abgr >> 16 & 0xFF) / 2, g = (abgr >> 8 & 0xFF) / 2, r = (abgr & 0xFF) / 2;
        return a << 24 | b << 16 | g << 8 | r;
    }

    private int sx(double worldX) { return mapX + (int) Math.round((worldX - (cx - radius)) * mapSize / size); }
    private int sz(double worldZ) { return mapY + (int) Math.round((worldZ - (cz - radius)) * mapSize / size); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (renderSizeHint(g, mouseX, mouseY, delta)) return;
        renderBackdrop(g);
        RpgFrame.panel(g, left, top, panelW, panelH);
        RpgFrame.header(g, left + 4, top + 4, panelW - 8, 20);
        g.drawString(font, title, left + 12, top + 10, VillageTheme.ON_BLUE, false);
        var stage = Component.translatable("map.livingkingdoms.stage", Component.translatable("stage.livingkingdoms." + data.getString("stage")), data.getInt("rank"));
        g.drawString(font, stage, left + panelW - 12 - font.width(stage), top + 10, VillageTheme.GOLD, false);
        RpgFrame.inset(g, mapX - 2, mapY - 2, mapSize + 4, mapSize + 4);
        if (texture != null) g.blit(TEXTURE, mapX, mapY, mapSize, mapSize, 0, 0, size, size, size, size);
        List<Component> hover = new ArrayList<>();
        // Walls ring.
        if (data.contains("wall_tier")) {
            int r = data.getInt("wall_radius"); int color = data.getBoolean("wall_done") ? 0xFFB0B0B8 : 0x99B0B0B8;
            for (int i = 0; i < 180; i++) {
                double a = Math.PI * 2 * i / 180;
                int px = sx(cx + .5 + Math.cos(a) * r), pz = sz(cz + .5 + Math.sin(a) * r);
                g.fill(px, pz, px + 1, pz + 1, color);
            }
        }
        for (var row : list("buildings")) {
            int x0 = sx(row.getInt("x0")), z0 = sz(row.getInt("z0")), x1 = sx(row.getInt("x1") + 1), z1 = sz(row.getInt("z1") + 1);
            int color = color(row.getString("kind"));
            g.fill(x0, z0, x1, z1, color & 0xAAFFFFFF);
            outline(g, x0, z0, x1, z1, color | 0xFF000000);
            if (inside(mouseX, mouseY, x0, z0, x1, z1)) hover.add(row.getString("kind").equals("house") ? Component.translatable("map.livingkingdoms.house")
                    : Component.translatable("map.livingkingdoms.building", Component.translatable("construction.livingkingdoms.building." + row.getString("kind")), Math.max(1, row.getInt("level"))));
        }
        for (var row : list("sites")) {
            int x0 = sx(row.getInt("x0")), z0 = sz(row.getInt("z0")), x1 = sx(row.getInt("x1") + 1), z1 = sz(row.getInt("z1") + 1);
            boolean blink = (net.minecraft.Util.getMillis() / 500) % 2 == 0;
            outline(g, x0, z0, x1, z1, blink ? 0xFFFFD34D : 0xFFB8862B);
            if (inside(mouseX, mouseY, x0, z0, x1, z1)) hover.add(Component.translatable("map.livingkingdoms.site",
                    Component.translatable("construction.livingkingdoms.building." + row.getString("kind")), row.getInt("percent")));
        }
        int away = 0;
        for (var row : list("citizens")) {
            if (row.getBoolean("away")) { away++; continue; }
            if (!row.contains("px")) continue;
            int px = sx(row.getInt("px") + .5), pz = sz(row.getInt("pz") + .5);
            g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF1A1A1A);
            g.fill(px - 1, pz - 1, px + 1, pz + 1, professionColor(row.getString("profession")));
            if (Math.abs(mouseX - px) <= 3 && Math.abs(mouseY - pz) <= 3) {
                var line = Component.literal(row.getString("name") + " · ").append(Component.translatable("profession.livingkingdoms." + row.getString("profession")));
                if (row.contains("work")) line.append(" · ").append(Component.translatable("work.livingkingdoms." + row.getString("work")));
                hover.add(line);
            }
        }
        // Treasure: only a hint circle, the hunt is still yours.
        var event = data.getCompound("event");
        if (event.contains("distance") && event.contains("tx")) {
            int tx = sx(event.getInt("tx")), tz = sz(event.getInt("tz")); int r = Math.max(4, 12 * mapSize / size);
            for (int i = 0; i < 48; i++) { double a = Math.PI * 2 * i / 48; g.fill(tx + (int) (Math.cos(a) * r), tz + (int) (Math.sin(a) * r), tx + (int) (Math.cos(a) * r) + 1, tz + (int) (Math.sin(a) * r) + 1, 0xFFFFE066); }
            if (Math.hypot(mouseX - tx, mouseY - tz) <= r) hover.add(Component.translatable("map.livingkingdoms.treasure"));
        }
        // Player arrow.
        int ppx = sx(data.getInt("px") + .5), ppz = sz(data.getInt("pz") + .5);
        g.fill(ppx - 2, ppz - 2, ppx + 3, ppz + 3, 0xFFFFFFFF); g.fill(ppx - 1, ppz - 1, ppx + 2, ppz + 2, 0xFFE24B4B);
        // Side panel.
        int sideX = mapX + mapSize + 14, y = mapY;
        g.drawString(font, Component.translatable("map.livingkingdoms.legend"), sideX, y, VillageTheme.MUTED, false); y += 14;
        for (var kind : new String[]{"town_hall", "house", "farm", "barracks", "watchtower", "market"}) {
            g.fill(sideX, y + 1, sideX + 7, y + 8, color(kind) | 0xFF000000);
            g.drawString(font, kind.equals("house") ? Component.translatable("map.livingkingdoms.house") : Component.translatable("construction.livingkingdoms.building." + kind), sideX + 11, y, VillageTheme.INK, false);
            y += 11;
        }
        y += 6;
        if (away > 0) { g.drawString(font, Component.translatable("map.livingkingdoms.away", away), sideX, y, VillageTheme.INK, false); y += 11; }
        if (data.contains("wall_tier")) { g.drawString(font, Component.translatable("map.livingkingdoms.wall", Component.translatable("wall.livingkingdoms.tier." + data.getInt("wall_tier"))), sideX, y, VillageTheme.INK, false); y += 11; }
        if (!hover.isEmpty()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (var line : hover) lines.add(line.getVisualOrderText());
            g.renderTooltip(font, lines, mouseX, mouseY);
        } else if (inside(mouseX, mouseY, mapX, mapY, mapX + mapSize, mapY + mapSize)) {
            int wx = cx - radius + (mouseX - mapX) * size / mapSize, wz = cz - radius + (mouseY - mapY) * size / mapSize;
            g.renderTooltip(font, Component.literal(wx + ", " + wz), mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, delta);
    }

    private List<CompoundTag> list(String key) {
        var out = new ArrayList<CompoundTag>(); var list = data.getList(key, 10);
        for (int i = 0; i < list.size(); i++) out.add(list.getCompound(i));
        return out;
    }
    private static boolean inside(int mx, int my, int x0, int z0, int x1, int z1) { return mx >= x0 && mx < Math.max(x1, x0 + 2) && my >= z0 && my < Math.max(z1, z0 + 2); }
    private static void outline(GuiGraphics g, int x0, int z0, int x1, int z1, int color) {
        x1 = Math.max(x1, x0 + 2); z1 = Math.max(z1, z0 + 2);
        g.fill(x0, z0, x1, z0 + 1, color); g.fill(x0, z1 - 1, x1, z1, color); g.fill(x0, z0, x0 + 1, z1, color); g.fill(x1 - 1, z0, x1, z1, color);
    }
    private static int color(String kind) {
        return switch (kind) {
            case "core", "town_hall" -> 0x00E0B040; case "house" -> 0x00C77B4A; case "farm" -> 0x0078B83A; case "barracks" -> 0x00B84040;
            case "watchtower" -> 0x008C8C9C; case "market" -> 0x00E07A30; case "warehouse" -> 0x008A6A40; case "lumberjack_hut" -> 0x005E8C3A;
            case "mine" -> 0x00606070; case "blacksmith" -> 0x00404050; default -> 0x00A0A0A0;
        };
    }
    private static int professionColor(String profession) {
        return switch (profession) {
            case "mayor" -> 0xFFFFD700; case "farmer" -> 0xFF8BD14E; case "guard" -> 0xFFE05050; case "builder" -> 0xFFF0A030;
            case "merchant" -> 0xFF50C8E0; case "lumberjack" -> 0xFF4E9A3A; case "miner" -> 0xFFA0A0B0; case "blacksmith" -> 0xFF707080; default -> 0xFFFFFFFF;
        };
    }

    @Override public void removed() {
        if (texture != null) { Minecraft.getInstance().getTextureManager().release(TEXTURE); texture = null; }
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}

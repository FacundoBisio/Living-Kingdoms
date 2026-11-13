package dev.livingkingdoms.client;

import dev.livingkingdoms.rpg.domain.WallDesign;
import dev.livingkingdoms.rpg.domain.WallGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.List;

/**
 * 30-second client-side drawing of the wall ring from the Walls tab: ring line, 3-wide main gates and tower spots.
 * Geometry is built once per toggle; ground heights are re-read (loaded chunks only) when the player changes chunk.
 * Driven by TerritoryOverlay's tick/render/logout hooks. No blocks, entities or server requests.
 */
public final class WallPreviewOverlay {
    public static final int DURATION_TICKS = 600, MAX_COLUMNS = 736, UNLOADED = Integer.MIN_VALUE;
    private static String dimension;
    private static int remainingTicks, viewX = Integer.MIN_VALUE, viewZ = Integer.MIN_VALUE;
    private static int[] xs = {}, zs = {}, ys = {};
    private static boolean[] gates = {};
    private static List<Integer> towers = List.of();
    private WallPreviewOverlay() {}

    public static boolean active() { return dimension != null; }

    /** Shows the ring the next order would raise (or the one rising now) from the management {@code wall} tag. */
    public static boolean show(CompoundTag wall) {
        clear();
        boolean rising = wall.getInt("tier") > 0 && !wall.getBoolean("complete") && wall.contains("ringRadius");
        int radius = wall.getInt(rising ? "ringRadius" : "radius");
        if (radius < 8 || radius > 96 || !wall.contains("cx") || wall.getString("dimension").isEmpty()) return false;
        WallGeometry geo;
        try { geo = WallGeometry.of(radius, new WallDesign(wall.getInt("gates"), wall.getBoolean("towers"), wall.getBoolean("gatehouses"))); }
        catch (IllegalArgumentException invalid) { return false; }
        int n = Math.min(geo.size(), MAX_COLUMNS), cx = wall.getInt("cx"), cz = wall.getInt("cz");
        xs = new int[n]; zs = new int[n]; ys = new int[n]; gates = new boolean[n]; java.util.Arrays.fill(ys, UNLOADED);
        for (int i = 0; i < n; i++) { int[] o = geo.ring().get(i); xs[i] = cx + o[0]; zs[i] = cz + o[1]; gates[i] = geo.gate(i); }
        towers = geo.towerCenters().stream().filter(t -> t < n).toList();
        dimension = wall.getString("dimension"); remainingTicks = DURATION_TICKS;
        return true;
    }
    public static void clear() {
        dimension = null; remainingTicks = 0; viewX = viewZ = Integer.MIN_VALUE;
        xs = zs = ys = new int[0]; gates = new boolean[0]; towers = List.of();
    }
    static void onTick() {
        if (dimension == null) return;
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !dimension.equals(client.level.dimension().location().toString())) { clear(); return; }
        if (client.isPaused()) return;
        if (--remainingTicks <= 0) { clear(); return; }
        var position = client.player.chunkPosition();
        if (position.x == viewX && position.z == viewZ) return;
        viewX = position.x; viewZ = position.z;
        // At most MAX_COLUMNS heightmap reads per chunk crossed; unloaded columns are skipped, never loaded.
        for (int i = 0; i < xs.length; i++) ys[i] = client.level.getChunkSource().getChunk(xs[i] >> 4, zs[i] >> 4, ChunkStatus.FULL, false) == null
                ? UNLOADED : client.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xs[i], zs[i]);
    }
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || dimension == null || xs.length == 0) return;
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.options.hideGui) return;
        var camera = event.getCamera().getPosition(); var pose = event.getPoseStack(); var transform = pose.last();
        var buffers = client.renderBuffers().bufferSource(); var consumer = buffers.getBuffer(RenderType.lines());
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (ys[i] == UNLOADED || ys[j] == UNLOADED) continue;
            float x1 = (float) (xs[i] + .5 - camera.x), y1 = (float) (ys[i] + .1 - camera.y), z1 = (float) (zs[i] + .5 - camera.z);
            float x2 = (float) (xs[j] + .5 - camera.x), y2 = (float) (ys[j] + .1 - camera.y), z2 = (float) (zs[j] + .5 - camera.z);
            float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1, length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length == 0) continue;
            consumer.addVertex(transform, x1, y1, z1).setColor(1f, .72f, .16f, 1f).setNormal(transform, dx / length, dy / length, dz / length);
            consumer.addVertex(transform, x2, y2, z2).setColor(1f, .72f, .16f, 1f).setNormal(transform, dx / length, dy / length, dz / length);
        }
        for (int i = 0; i < n; i++) if (gates[i] && ys[i] != UNLOADED) {
            double x = xs[i] - camera.x, y = ys[i] - camera.y, z = zs[i] - camera.z;
            LevelRenderer.renderLineBox(pose, consumer, x, y, z, x + 1, y + 3, z + 1, .2f, .95f, .3f, 1f);
        }
        for (int t : towers) if (ys[t] != UNLOADED) {
            double x = xs[t] - camera.x, y = ys[t] - camera.y, z = zs[t] - camera.z;
            LevelRenderer.renderLineBox(pose, consumer, x - 1, y, z - 1, x + 2, y + 6, z + 2, .35f, .6f, 1f, .8f);
        }
        buffers.endBatch(RenderType.lines());
    }
}

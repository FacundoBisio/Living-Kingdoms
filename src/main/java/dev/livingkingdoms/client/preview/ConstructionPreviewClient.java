package dev.livingkingdoms.client.preview;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.livingkingdoms.construction.preview.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Original NBT block models, oriented by the server. No fake level or client block mutation. */
public final class ConstructionPreviewClient {
    private static final Map<PreviewPayloads.Action,KeyMapping> KEYS = new EnumMap<>(PreviewPayloads.Action.class);
    private static CompoundTag data = new CompoundTag();
    private record RenderCell(PreviewGeometry.Cell cell, net.minecraft.world.level.block.entity.BlockEntity entity,
                              net.minecraft.resources.ResourceLocation atlas) {}
    private static List<RenderCell> cells = List.of();
    private static int remaining;
    private ConstructionPreviewClient() {}
    public static void register(IEventBus bus) {
        PreviewPayloads.receiver = ConstructionPreviewClient::receive;
        bus.addListener(ConstructionPreviewClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(ConstructionPreviewClient::tick);
        NeoForge.EVENT_BUS.addListener(ConstructionPreviewClient::render);
        NeoForge.EVENT_BUS.addListener(ConstructionPreviewClient::hud);
        NeoForge.EVENT_BUS.addListener(ConstructionPreviewClient::logout);
    }
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        for (var action : PreviewPayloads.Action.values()) {
            int key = switch (action) { case ROTATE -> GLFW.GLFW_KEY_R; case MOVE -> GLFW.GLFW_KEY_G;
                case CONFIRM -> GLFW.GLFW_KEY_ENTER; case CANCEL -> GLFW.GLFW_KEY_BACKSPACE; };
            var mapping = new KeyMapping("key.livingkingdoms.preview." + action.name().toLowerCase(Locale.ROOT),
                    KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,key,"key.categories.livingkingdoms");
            KEYS.put(action,mapping); event.register(mapping);
        }
    }
    private static void receive(CompoundTag next) {
        data = next.copy(); cells = List.of(); remaining = Math.min(PreviewSession.LIFETIME,data.getInt("ticks"));
        if (!data.hasUUID("session")) return;
        var list = data.getList("blocks",10); var read = new ArrayList<RenderCell>();
        for (int i = 0; i < Math.min(list.size(),PreviewGeometry.MAX_BLOCKS); i++) {
            var b = list.getCompound(i); var cell = new PreviewGeometry.Cell(BlockPos.of(b.getLong("pos")),Block.stateById(b.getInt("state")));
            net.minecraft.world.level.block.entity.BlockEntity entity = null;
            var atlas = TextureAtlas.LOCATION_BLOCKS;
            var block = cell.state().getBlock();
            // Only native renderers whose dependencies and texture formats are known here.
            // Unsupported animated blocks retain the footprint; never fall back to an item renderer.
            boolean nativeEntity = block instanceof net.minecraft.world.level.block.AbstractChestBlock<?>
                    || block instanceof net.minecraft.world.level.block.BedBlock
                    || block instanceof net.minecraft.world.level.block.SignBlock
                    || block instanceof net.minecraft.world.level.block.AbstractBannerBlock
                    || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                    || block instanceof net.minecraft.world.level.block.AbstractSkullBlock
                    || block instanceof net.minecraft.world.level.block.BellBlock;
            if (nativeEntity && block instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                entity = entityBlock.newBlockEntity(cell.position(),cell.state());
                if (entity != null) entity.setLevel(Minecraft.getInstance().level);
                if (entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                        || entity instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity) atlas = Sheets.CHEST_SHEET;
                else if (entity instanceof net.minecraft.world.level.block.entity.BedBlockEntity) atlas = Sheets.BED_SHEET;
                else if (entity instanceof net.minecraft.world.level.block.entity.SignBlockEntity) atlas = Sheets.SIGN_SHEET;
                else if (entity instanceof net.minecraft.world.level.block.entity.BannerBlockEntity) atlas = Sheets.BANNER_SHEET;
                else if (entity instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) atlas = Sheets.SHULKER_SHEET;
                else if (entity instanceof net.minecraft.world.level.block.entity.SkullBlockEntity
                        && block instanceof net.minecraft.world.level.block.AbstractSkullBlock skull)
                    atlas = net.minecraft.client.renderer.blockentity.SkullBlockRenderer.SKIN_BY_TYPE.get(skull.getType());
                if (atlas == null || entity != null && !entity.getType().isValid(cell.state())) entity = null;
            }
            read.add(new RenderCell(cell,entity,atlas));
        }
        cells = List.copyOf(read);
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof dev.livingkingdoms.client.VillageScreen) mc.setScreen(null);
    }
    private static void clear() { data = new CompoundTag(); cells = List.of(); remaining = 0; }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    private static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (data.hasUUID("session") && (mc.player == null || mc.level == null || !mc.player.isAlive()
                || !data.getString("dimension").equals(mc.level.dimension().location().toString()) || --remaining <= 0)) clear();
        for (var binding : KEYS.entrySet()) while (binding.getValue().consumeClick()) {
            if (!data.hasUUID("session") || mc.screen != null || mc.player == null) continue;
            var action = binding.getKey();
            if (action == PreviewPayloads.Action.CONFIRM && !data.getBoolean("valid")) continue;
            var origin = BlockPos.of(data.getLong("origin"));
            var rotation = Rotation.valueOf(data.getString("rotation"));
            if (action == PreviewPayloads.Action.ROTATE) rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
            if (action == PreviewPayloads.Action.MOVE) {
                var hit = mc.player.pick(48,1,false);
                if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) continue;
                origin = block.getBlockPos().above();
            }
            PacketDistributor.sendToServer(new PreviewPayloads.Request(data.getUUID("session"),action,origin,rotation));
            if (action == PreviewPayloads.Action.CANCEL || action == PreviewPayloads.Action.CONFIRM) clear();
        }
    }
    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !data.hasUUID("session")) return;
        var mc = Minecraft.getInstance(); if (mc.level == null || mc.player == null) return;
        var camera = event.getCamera().getPosition(); var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var type = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
        var usedTypes = new LinkedHashSet<RenderType>(); usedTypes.add(type);
        // Vertex alpha is applied explicitly; shader color alone does not affect batched block model vertices.
        MultiBufferSource ghostBuffers = ignored -> new Fade(buffers.getBuffer(type));
        for (var rendered : cells) {
            var cell = rendered.cell(); var pos = cell.position();
            if (!mc.level.hasChunkAt(pos) || mc.player.distanceToSqr(pos.getX(),pos.getY(),pos.getZ()) > 128 * 128) continue;
            pose.pushPose(); pose.translate(pos.getX()-camera.x,pos.getY()-camera.y,pos.getZ()-camera.z);
            if (cell.state().getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL)
                mc.getBlockRenderer().renderSingleBlock(cell.state(),pose,ghostBuffers,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY,type);
            if (rendered.entity() != null) {
                // Unattached render-only block entities preserve bed/chest orientation and their native atlas.
                // They are never installed in a client chunk, ticked, or sent back to the server.
                mc.getBlockEntityRenderDispatcher().renderItem(rendered.entity(),pose,requested -> {
                    // Banner poles/base use the blocks atlas, patterns use the banner atlas.
                    var atlas = rendered.entity() instanceof net.minecraft.world.level.block.entity.BannerBlockEntity
                            && requested != Sheets.bannerSheet() ? TextureAtlas.LOCATION_BLOCKS : rendered.atlas();
                    var entityType = RenderType.entityTranslucent(atlas); usedTypes.add(entityType);
                    return new Fade(buffers.getBuffer(entityType));
                },LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
            }
            pose.popPose();
        }
        usedTypes.forEach(buffers::endBatch);
        var lines = buffers.getBuffer(RenderType.lines()); var low = BlockPos.of(data.getLong("origin")); var high = BlockPos.of(data.getLong("high"));
        boolean valid = data.getBoolean("valid"); float red = valid ? .2f : 1f, green = valid ? 1f : .2f;
        LevelRenderer.renderLineBox(pose,lines,low.getX()-camera.x,low.getY()-camera.y,low.getZ()-camera.z,
                high.getX()+1-camera.x,high.getY()+1-camera.y,high.getZ()+1-camera.z,red,green,.3f,.8f);
        LevelRenderer.renderLineBox(pose,lines,low.getX()-camera.x,low.getY()+.03-camera.y,low.getZ()-camera.z,
                high.getX()+1-camera.x,low.getY()+.06-camera.y,high.getZ()+1-camera.z,red,green,.3f,1f);
        var entry = BlockPos.of(data.getLong("entry"));
        LevelRenderer.renderLineBox(pose,lines,entry.getX()-camera.x,entry.getY()-camera.y,entry.getZ()-camera.z,
                entry.getX()+1-camera.x,entry.getY()+2-camera.y,entry.getZ()+1-camera.z,.2f,.7f,1f,1f);
        buffers.endBatch(RenderType.lines());
    }
    private static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance(); if (!data.hasUUID("session") || mc.screen != null || mc.options.hideGui) return;
        var graphics = event.getGuiGraphics(); var font = mc.font; var lines = new ArrayList<Component>();
        lines.add(Component.translatable("preview.livingkingdoms.title",Component.translatable("construction.livingkingdoms.building."+data.getString("kind").toLowerCase(Locale.ROOT))));
        lines.add(Component.translatable(data.getBoolean("valid") ? "preview.livingkingdoms.valid" : "preview.livingkingdoms.invalid"));
        var cost = Component.translatable("preview.livingkingdoms.cost"); var costs = data.getCompound("cost");
        for (String kind : new TreeSet<>(costs.getAllKeys())) cost.append(" ").append(Component.translatable("quest.livingkingdoms.resource."+kind.toLowerCase(Locale.ROOT))).append(" ×"+costs.getInt(kind));
        lines.add(cost);
        for (var action : PreviewPayloads.Action.values()) lines.add(Component.translatable("preview.livingkingdoms.control",KEYS.get(action).getTranslatedKeyMessage(),
                Component.translatable("key.livingkingdoms.preview."+action.name().toLowerCase(Locale.ROOT))));
        lines.add(Component.translatable("preview.livingkingdoms.entry"));
        if (data.getBoolean("simplified")) lines.add(Component.translatable("preview.livingkingdoms.simplified"));
        int width = Math.min(320,graphics.guiWidth()-16); var wrapped = lines.stream().flatMap(line -> font.split(line,width).stream()).toList();
        int y = Math.max(8,graphics.guiHeight()-wrapped.size()*10-48);
        graphics.fill(4,y-4,width+12,y+wrapped.size()*10+4,0xB010161D);
        for (var line : wrapped) { graphics.drawString(font,line,8,y,0xFFFFFF,true); y += 10; }
    }
    private record Fade(VertexConsumer delegate) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x,float y,float z) { delegate.addVertex(x,y,z); return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { delegate.setColor(r,g,b,Math.min(a,95)); return this; }
        @Override public VertexConsumer setUv(float u,float v) { delegate.setUv(u,v); return this; }
        @Override public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v); return this; }
        @Override public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v); return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z); return this; }
    }
}

package dev.livingkingdoms.client.citizenvisual;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.livingkingdoms.profession.domain.ProfessionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.EnumMap;

/** Delegated instance only; never replaces EntityType.VILLAGER's renderer registry entry. */
final class CitizenHumanoidRenderer extends MobRenderer<Villager, CitizenHumanoidModel> {
    private CitizenVisualClient.View presentation;
    CitizenHumanoidRenderer(EntityRendererProvider.Context context) {
        super(context, new CitizenHumanoidModel(context.bakeLayer(ModelLayers.PLAYER)), .5F);
        addLayer(new Tools(this, context));
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
    }
    void present(CitizenVisualClient.View view, long actionAt) {
        presentation = view; model.presentation = view.data(); model.actionAt = actionAt;
    }
    @Override public ResourceLocation getTextureLocation(Villager npc) { return presentation.texture(); }
    @Override protected boolean shouldShowName(Villager npc) {
        return CitizenVisualConfig.LABELS.get() && Minecraft.renderNames() && !npc.isInvisible() && !npc.isSleeping()
                && super.shouldShowName(npc) && entityRenderDispatcher.distanceToSqr(npc)
                <= (double) CitizenVisualConfig.NAME_DISTANCE.get() * CitizenVisualConfig.NAME_DISTANCE.get();
    }
    @Override protected void renderNameTag(Villager npc, Component ignored, PoseStack pose, MultiBufferSource buffers, int light, float partial) {
        if (!shouldShowName(npc)) return;
        pose.pushPose();
        pose.translate(0, npc.getBbHeight() + .35, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation()); pose.scale(.025F, -.025F, .025F);
        int background = (int) (Minecraft.getInstance().options.getBackgroundOpacity(.25F) * 255) << 24;
        line(presentation.name(), 0, -1, pose, buffers, background, light);
        int distance = Math.min(CitizenVisualConfig.SECONDARY_DISTANCE.get(), CitizenVisualConfig.NAME_DISTANCE.get());
        if (distance > 0 && entityRenderDispatcher.distanceToSqr(npc) <= (double) distance * distance)
            line(presentation.secondary(), 10, 0xFFE2C07A, pose, buffers, background, light);
        pose.popPose();
    }
    private void line(Component text, int y, int color, PoseStack pose, MultiBufferSource buffers, int background, int light) {
        getFont().drawInBatch(text, -getFont().width(text) / 2F, y, color, false, pose.last().pose(),
                buffers, Font.DisplayMode.NORMAL, background, light);
    }

    private static final class Tools extends ItemInHandLayer<Villager, CitizenHumanoidModel> {
        private final EnumMap<ProfessionType, ItemStack> tools = new EnumMap<>(ProfessionType.class);
        Tools(CitizenHumanoidRenderer parent, EntityRendererProvider.Context context) {
            super(parent, context.getItemInHandRenderer());
            tools.put(ProfessionType.FARMER, new ItemStack(Items.IRON_HOE));
            tools.put(ProfessionType.LUMBERJACK, new ItemStack(Items.IRON_AXE));
            tools.put(ProfessionType.MINER, new ItemStack(Items.IRON_PICKAXE));
            tools.put(ProfessionType.BUILDER, new ItemStack(Items.STONE_AXE));
            tools.put(ProfessionType.BLACKSMITH, new ItemStack(Items.IRON_AXE));
            tools.put(ProfessionType.MERCHANT, new ItemStack(Items.EMERALD));
            tools.put(ProfessionType.MAYOR, new ItemStack(Items.WRITABLE_BOOK));
        }
        @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, Villager npc,
                float walk, float speed, float partial, float age, float yaw, float pitch) {
            if (npc.isInvisible() || npc.isSleeping()) return;
            super.render(pose, buffers, light, npc, walk, speed, partial, age, yaw, pitch);
            var data = getParentModel().presentation;
            if (data == null || !data.showsProfessionTool() || !npc.getMainHandItem().isEmpty()) return;
            ItemStack tool = tools.get(data.profession());
            if (tool == null) return;
            boolean left = npc.getMainArm() == HumanoidArm.LEFT;
            renderArmWithItem(npc, tool, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    npc.getMainArm(), pose, buffers, light);
        }
    }
}

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
import dev.livingkingdoms.citizenvisual.ArgonAnimationSet;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Delegated instance only; never replaces EntityType.VILLAGER's renderer registry entry. */
final class CitizenHumanoidRenderer extends MobRenderer<Villager, CitizenHumanoidModel> {
    private CitizenVisualClient.View presentation;
    private final CitizenHumanoidModel nativeModel;
    private CitizenHumanoidModel argonModel;
    private net.minecraft.client.model.geom.ModelPart hammer;
    private ArgonAnimationSet animations;
    private static final ResourceLocation HAMMER_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "livingkingdoms", "textures/entity/argon4/builder.png");
    CitizenHumanoidRenderer(EntityRendererProvider.Context context) {
        super(context, new CitizenHumanoidModel(context.bakeLayer(ModelLayers.PLAYER)), .5F);
        nativeModel = model;
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            animations = ArgonAnimationSet.parse(animation(resources, "citizen"), animation(resources, "professions"), animation(resources, "combat"));
            for (String required : new String[]{"idle", "work_hammer", "farm_plant", "lumberjack_chop", "guard_block"})
                animations.clip("animation.citizen." + required);
            argonModel = new CitizenHumanoidModel(ArgonCitizenGeometry.createRoot(resources), animations);
            hammer = ArgonCitizenGeometry.createHammer(resources);
        } catch (IOException | RuntimeException exception) {
            argonModel = null; hammer = null;
            org.slf4j.LoggerFactory.getLogger(CitizenHumanoidRenderer.class)
                    .warn("Invalid Argon citizen resources; using the stable native model until resource reload", exception);
        }
        addLayer(new Tools(this, context));
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
    }
    void present(CitizenVisualClient.View view, long actionAt) {
        model = CitizenVisualConfig.MODEL.get() == CitizenVisualConfig.ModelStyle.ARGON4 && argonModel != null ? argonModel : nativeModel;
        presentation = view; model.presentation = view.data(); model.actionAt = actionAt;
    }
    @Override public ResourceLocation getTextureLocation(Villager npc) { return model.argon() ? presentation.argonTexture() : presentation.texture(); }
    private static String animation(net.minecraft.server.packs.resources.ResourceManager resources, String name) throws IOException {
        var id = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "animations/argon4/" + name + ".animation.json");
        try (var stream = resources.getResourceOrThrow(id).open()) {
            byte[] bytes = stream.readNBytes(ArgonAnimationSet.MAX_DOCUMENT_CHARS + 1);
            if (bytes.length > ArgonAnimationSet.MAX_DOCUMENT_CHARS) throw new IOException("Animation exceeds resource budget: " + id);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
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
        private final CitizenHumanoidRenderer citizenRenderer;
        Tools(CitizenHumanoidRenderer parent, EntityRendererProvider.Context context) {
            super(parent, context.getItemInHandRenderer());
            citizenRenderer = parent;
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
            if (getParentModel().argon() && citizenRenderer.hammer != null
                    && (data.profession() == ProfessionType.BUILDER || data.profession() == ProfessionType.BLACKSMITH)) {
                boolean left = npc.getMainArm() == HumanoidArm.LEFT;
                pose.pushPose();
                getParentModel().translateToHand(npc.getMainArm(), pose);
                if (left) pose.scale(-1, 1, 1);
                var hammer = citizenRenderer.hammer;
                hammer.xRot = hammer.yRot = hammer.zRot = 0;
                float elapsed = npc.level().getGameTime() - getParentModel().actionAt + partial;
                if (CitizenVisualConfig.WORK_ANIMATIONS.get() && attackTimeSafe(npc)
                        && dev.livingkingdoms.citizenvisual.ArgonActivity.workClip(data, elapsed, speed, false, npc.isPassenger(), false) != null) {
                    hammer.xRot = citizenRenderer.animations.clip("animation.citizen.work_hammer").sample(
                            ArgonAnimationSet.Bone.RIGHT_HAND, ArgonAnimationSet.Transform.ROTATION, 0, elapsed / 20F) * net.minecraft.util.Mth.DEG_TO_RAD;
                }
                hammer.render(pose, buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(HAMMER_TEXTURE)), light,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                pose.popPose(); return;
            }
            ItemStack tool = tools.get(data.profession());
            if (tool == null) return;
            boolean left = npc.getMainArm() == HumanoidArm.LEFT;
            renderArmWithItem(npc, tool, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    npc.getMainArm(), pose, buffers, light);
        }
        private static boolean attackTimeSafe(Villager npc) { return !npc.swinging; }
    }
}

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
        addLayer(new Crown(this));
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
        double distanceSqr = entityRenderDispatcher.distanceToSqr(npc);
        int nameDistance = CitizenVisualConfig.NAME_DISTANCE.get();
        int secondary = Math.min(CitizenVisualConfig.SECONDARY_DISTANCE.get(), nameDistance);
        int activity = Math.min(CitizenVisualConfig.ACTIVITY_DISTANCE.get(), nameDistance);
        boolean showActivity = presentation.activity() != null && activity > 0 && distanceSqr <= (double) activity * activity;
        boolean showSecondary = secondary > 0 && distanceSqr <= (double) secondary * secondary;
        int lines = 1 + (showSecondary ? 1 : 0) + (showActivity ? 1 : 0);
        pose.pushPose();
        // The lowest line sits clearly above the head (and above the Mayor's crown); extra lines stack upward.
        boolean crowned = presentation.data() != null && presentation.data().profession() == ProfessionType.MAYOR;
        pose.translate(0, npc.getBbHeight() + .55 + (crowned ? .3 : 0) + (lines - 1) * .25, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation()); pose.scale(.025F, -.025F, .025F);
        int background = (int) (Minecraft.getInstance().options.getBackgroundOpacity(.25F) * 255) << 24;
        // Like vanilla name tags: a faint see-through pass keeps names readable behind blocks and other
        // entities, then the normal pass draws them crisp when in view.
        boolean discrete = !npc.isDiscrete();
        line(presentation.name(), 0, -1, pose, buffers, background, light, discrete);
        if (showSecondary)
            line(presentation.secondary(), 10, 0xFFE2C07A, pose, buffers, background, light, discrete);
        if (showActivity) line(presentation.activity(), 20, 0xFFB8B8B8, pose, buffers, background, light, discrete);
        pose.popPose();
    }
    private void line(Component text, int y, int color, PoseStack pose, MultiBufferSource buffers, int background, int light, boolean seeThrough) {
        float x = -getFont().width(text) / 2F;
        if (seeThrough) getFont().drawInBatch(text, x, y, 0x40FFFFFF & color | 0x40000000, false, pose.last().pose(),
                buffers, Font.DisplayMode.SEE_THROUGH, background, light);
        getFont().drawInBatch(text, x, y, color, false, pose.last().pose(),
                buffers, Font.DisplayMode.NORMAL, seeThrough ? 0 : background, light);
    }

    /**
     * Gold crown with rubies and a glowing sapphire on the Mayor's head, so the leader reads at a glance from across
     * the plaza. UV islands in {@code mayor_crown.png} match these boxes one to one (64x32 texture).
     */
    private static final class Crown extends net.minecraft.client.renderer.entity.layers.RenderLayer<Villager, CitizenHumanoidModel> {
        static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "textures/entity/mayor_crown.png");
        private final net.minecraft.client.model.geom.ModelPart crown, jewel;
        Crown(CitizenHumanoidRenderer parent) {
            super(parent);
            crown = part(net.minecraft.client.model.geom.builders.CubeListBuilder.create()
                    .texOffs(0, 0).addBox(-4.6F, -9.2F, -4.6F, 9.2F, 1.6F, 1F)
                    .texOffs(22, 0).addBox(-4.6F, -9.2F, 3.6F, 9.2F, 1.6F, 1F)
                    .texOffs(44, 0).addBox(-4.6F, -9.2F, -3.6F, 1F, 1.6F, 7.2F)
                    .texOffs(0, 10).addBox(3.6F, -9.2F, -3.6F, 1F, 1.6F, 7.2F)
                    .texOffs(18, 10).addBox(-0.7F, -12.2F, -4.6F, 1.4F, 3F, 1F)
                    .texOffs(24, 10).addBox(-3.6F, -11.2F, -4.6F, 1.2F, 2F, 1F)
                    .texOffs(30, 10).addBox(2.4F, -11.2F, -4.6F, 1.2F, 2F, 1F)
                    .texOffs(36, 10).addBox(-4.6F, -11.2F, -0.6F, 1F, 2F, 1.2F)
                    .texOffs(42, 10).addBox(3.6F, -11.2F, -0.6F, 1F, 2F, 1.2F)
                    .texOffs(48, 10).addBox(-0.7F, -11.2F, 3.6F, 1.4F, 2F, 1F)
                    .texOffs(54, 10).addBox(-2.8F, -8.9F, -4.9F, 1F, 1F, 0.5F)
                    .texOffs(58, 10).addBox(1.8F, -8.9F, -4.9F, 1F, 1F, 0.5F)
                    .texOffs(0, 20).addBox(-4.9F, -8.9F, -0.5F, 0.5F, 1F, 1F)
                    .texOffs(4, 20).addBox(4.4F, -8.9F, -0.5F, 0.5F, 1F, 1F));
            jewel = part(net.minecraft.client.model.geom.builders.CubeListBuilder.create().texOffs(8, 20).addBox(-0.6F, -11.4F, -4.95F, 1.2F, 1.2F, 0.5F));
        }
        private static net.minecraft.client.model.geom.ModelPart part(net.minecraft.client.model.geom.builders.CubeListBuilder cubes) {
            var mesh = new net.minecraft.client.model.geom.builders.MeshDefinition();
            mesh.getRoot().addOrReplaceChild("p", cubes, net.minecraft.client.model.geom.PartPose.ZERO);
            return net.minecraft.client.model.geom.builders.LayerDefinition.create(mesh, 64, 32).bakeRoot();
        }
        @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, Villager npc,
                float walk, float speed, float partial, float age, float yaw, float pitch) {
            var data = getParentModel().presentation;
            if (data == null || data.profession() != ProfessionType.MAYOR || npc.isInvisible()) return;
            pose.pushPose();
            getParentModel().getHead().translateAndRotate(pose);
            int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
            var buffer = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(TEXTURE));
            crown.render(pose, buffer, light, overlay);
            // The sapphire glows faintly even at night.
            jewel.render(pose, buffer, 0xF000F0, overlay);
            pose.popPose();
        }
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

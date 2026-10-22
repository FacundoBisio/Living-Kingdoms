package dev.livingkingdoms.client.warband;

import dev.livingkingdoms.citizenvisual.ArgonAnimationSet;
import dev.livingkingdoms.client.citizenvisual.ArgonCitizenGeometry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.monster.Vindicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Delegated instance only: never registered for EntityType.VINDICATOR, so vanilla vindicators keep their renderer. */
final class WarbandSoldierRenderer extends MobRenderer<Vindicator, WarbandSoldierModel> {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarbandSoldierRenderer.class);
    static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "textures/entity/argon4/warband_soldier.png");
    private static final ResourceLocation GEOMETRY = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "geo/argon4/warband_soldier.geo.json");
    private static final ResourceLocation ANIMATIONS = ResourceLocation.fromNamespaceAndPath("livingkingdoms", "animations/argon4/warband.animation.json");

    private WarbandSoldierRenderer(EntityRendererProvider.Context context, WarbandSoldierModel model) {
        super(context, model, .5F);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
    }

    /** Null when the resources are invalid: soldiers then keep the vanilla renderer until the next resource reload. */
    static WarbandSoldierRenderer create(EntityRendererProvider.Context context) {
        try {
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            ArgonAnimationSet animations = ArgonAnimationSet.parse(read(resources, ANIMATIONS));
            var attack = animations.clip("animation.warband.axe_attack");
            return new WarbandSoldierRenderer(context,
                    new WarbandSoldierModel(ArgonCitizenGeometry.createRawRoot(resources, GEOMETRY), attack));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Invalid warband soldier resources; using the vanilla renderer until resource reload", exception);
            return null;
        }
    }

    @Override public ResourceLocation getTextureLocation(Vindicator soldier) { return TEXTURE; }

    private static String read(ResourceManager resources, ResourceLocation id) throws IOException {
        try (var stream = resources.getResourceOrThrow(id).open()) {
            byte[] bytes = stream.readNBytes(ArgonAnimationSet.MAX_DOCUMENT_CHARS + 1);
            if (bytes.length > ArgonAnimationSet.MAX_DOCUMENT_CHARS) throw new IOException("Animation exceeds resource budget: " + id);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}

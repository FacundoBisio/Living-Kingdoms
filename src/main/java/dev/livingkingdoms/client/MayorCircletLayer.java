package dev.livingkingdoms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Small geometry-only leadership marker using a vanilla gold texture; no new entity or texture pipeline. */
public final class MayorCircletLayer extends RenderLayer<Villager,VillagerModel<Villager>> {
    private final ModelPart crown;
    private MayorCircletLayer(VillagerRenderer parent) {
        super(parent);
        MeshDefinition mesh=new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("circlet",CubeListBuilder.create().texOffs(0,0)
                .addBox(-4.5F,-8,-4.5F,9,1,1).addBox(-4.5F,-8,3.5F,9,1,1)
                .addBox(-4.5F,-8,-3.5F,1,1,7).addBox(3.5F,-8,-3.5F,1,1,7)
                .addBox(-1,-10,-4.5F,2,2,1).addBox(-4.5F,-9,-4.5F,1,1,1).addBox(3.5F,-9,-4.5F,1,1,1),PartPose.ZERO);
        crown=LayerDefinition.create(mesh,16,16).bakeRoot();
    }
    public static void register(EntityRenderersEvent.AddLayers event) {
        if(event.getRenderer(EntityType.VILLAGER) instanceof VillagerRenderer renderer) renderer.addLayer(new MayorCircletLayer(renderer));
    }
    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,Villager npc,float walk,float speed,float partial,float age,float yaw,float pitch) {
        if(!npc.isNoAi()||npc.getCustomName()==null||!(npc.getCustomName().getContents() instanceof TranslatableContents text)
                ||!text.getKey().equals("npc.livingkingdoms.mayor.name"))return;
        pose.pushPose();getParentModel().getHead().translateAndRotate(pose);
        crown.render(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(ResourceLocation.withDefaultNamespace("textures/block/gold_block.png"))),light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}

package dev.livingkingdoms.client.warband;

import dev.livingkingdoms.client.citizenvisual.CitizenVisualConfig;
import dev.livingkingdoms.war.WarbandAppearance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.entity.monster.Vindicator;

/**
 * Renders marked warband soldiers with the Argon 4 soldier model. Client presentation only: it never changes
 * entities, AI, combat or saved data, and the toggle lives in the client config next to the citizen options.
 * No common bootstrap references this class: register only from VillageClient.
 */
public final class WarbandVisualClient {
    private static WarbandSoldierRenderer renderer;

    private WarbandVisualClient() { }

    public static void register(IEventBus bus) {
        bus.addListener(WarbandVisualClient::layers);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, WarbandVisualClient::render);
    }

    private static void layers(EntityRenderersEvent.AddLayers event) {
        renderer = WarbandSoldierRenderer.create(event.getContext());
    }

    private static void render(RenderLivingEvent.Pre<?, ?> event) {
        if (renderer == null || event.getRenderer() == renderer || !CitizenVisualConfig.WARBAND_SOLDIERS.get()
                || !(event.getEntity() instanceof Vindicator soldier) || !WarbandAppearance.isSoldier(soldier)) return;
        event.setCanceled(true);
        renderer.render(soldier, soldier.getYRot(), event.getPartialTick(), event.getPoseStack(),
                event.getMultiBufferSource(), event.getPackedLight());
    }
}

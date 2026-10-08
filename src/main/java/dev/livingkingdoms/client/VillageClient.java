package dev.livingkingdoms.client;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.ui.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Loaded exclusively on the physical client; dedicated servers never resolve screen/render classes. */
@Mod(value = LivingKingdoms.MOD_ID, dist = Dist.CLIENT)
public final class VillageClient {
    public VillageClient(IEventBus bus) {
        bus.addListener(MayorCircletLayer::register);
        UiPayloads.clientReceiver = payload -> {
            Minecraft client = Minecraft.getInstance();
            String kind = payload.data().getString("screen");
            if (kind.equals("closed")) {
                if (client.screen instanceof VillageScreen) client.setScreen(null);
            } else if (client.screen instanceof VillageScreen screen && screen.sameSession(payload.data())) {
                screen.update(payload.data());
            } else if (kind.equals("board") || kind.equals("dialogue")) {
                client.setScreen(new VillageScreen(payload.data()));
            }
        };
    }
}

package dev.livingkingdoms.client;

import dev.livingkingdoms.LivingKingdoms;
import dev.livingkingdoms.ui.UiPayloads;
import dev.livingkingdoms.ui.UiSessionClosures;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import java.util.UUID;

/** Loaded exclusively on the physical client; dedicated servers never resolve screen/render classes. */
@Mod(value = LivingKingdoms.MOD_ID, dist = Dist.CLIENT)
public final class VillageClient {
    private static final UiSessionClosures CLOSED_SESSIONS=new UiSessionClosures(64);
    static void closeSession(UUID session) { CLOSED_SESSIONS.close(session); }
    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { CLOSED_SESSIONS.clear(); }
    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { CLOSED_SESSIONS.clear(); }
    public VillageClient(IEventBus bus,net.neoforged.fml.ModContainer container) {
        CommerceScreen.register();
        ManagementScreen.register();
        KingdomAlerts.register();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event)->ManagementScreen.clear());
        dev.livingkingdoms.client.citizenvisual.CitizenVisualClient.register(bus,container);
        dev.livingkingdoms.client.warband.WarbandVisualClient.register(bus);
        dev.livingkingdoms.client.preview.ConstructionPreviewClient.register(bus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event)->CommerceScreen.clear());
        bus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event)->event.register(dev.livingkingdoms.storage.KingdomStorage.MENU.get(),StorageScreen::new));
        bus.addListener(VillageClient::registerUiReload);
        bus.addListener(MayorCircletLayer::register);
        bus.addListener(KingdomKeys::register);
        bus.addListener(KingdomHud::register);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,dev.livingkingdoms.config.ClientHudConfig.SPEC);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(KingdomKeys::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(KingdomHud::onTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(KingdomHud::onLogout);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(QuestNotifications::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(QuestNotifications::logout);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(KingdomHud::onRenderGui);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(KingdomHud::onBossProgress);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(TerritoryOverlay::render);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(TerritoryOverlay::onTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(TerritoryOverlay::onLogout);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(VillageClient::onLogin);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(VillageClient::onLogout);
        UiPayloads.hudReceiver=payload -> KingdomHud.update(payload.data());
        UiPayloads.notificationReceiver=QuestNotifications::receive;
        UiPayloads.territoryReceiver=payload -> TerritoryOverlay.update(payload.data());
        UiPayloads.clientReceiver = payload -> {
            Minecraft client = Minecraft.getInstance();
            if(client.player==null || client.level==null) return;
            var data=payload.data();
            if(data.hasUUID("session") && CLOSED_SESSIONS.isClosed(data.getUUID("session"))) return;
            String kind = payload.data().getString("screen");
            if (kind.equals("closed")) {
                boolean matching=client.screen instanceof VillageScreen village && (!data.hasUUID("session") || village.sameSession(data))
                        || client.screen instanceof MayorOnboardingScreen mayor && (!data.hasUUID("session") || mayor.sameSession(data))
                        || client.screen instanceof StockScreen stock && (!data.hasUUID("session") || stock.sameSession(data));
                if(data.hasUUID("session")) closeSession(data.getUUID("session"));
                if(matching) client.setScreen(null);
            } else if(!data.hasUUID("session")) {
                return;
            } else if (kind.equals("dialogue") && payload.data().contains("onboarding_stage")
                    && !payload.data().getString("onboarding_stage").equals("JOINED")) {
                if (client.screen instanceof MayorOnboardingScreen screen && screen.sameSession(payload.data())) screen.update(payload.data());
                else client.setScreen(new MayorOnboardingScreen(payload.data()));
            } else if(kind.equals("stock")) {
                if(client.screen instanceof StockScreen stock && stock.sameSession(data)) stock.update(data);
                else client.setScreen(new StockScreen(data));
            } else if (client.screen instanceof VillageScreen screen && screen.sameSession(payload.data())) {
                screen.update(payload.data());
            } else if (kind.equals("board") || kind.equals("dialogue") || kind.equals("construction")
                    || kind.equals("citizens") || kind.equals("immigration")) {
                client.setScreen(new VillageScreen(payload.data()));
            }
        };
    }
    private static void registerUiReload(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
            var client=Minecraft.getInstance();
            client.execute(()-> {
                KingdomHud.invalidateLayout();
                QuestNotifications.reload();
                if(client.screen instanceof VillageScreen village) village.reloadUi();
                else if(client.screen instanceof MayorOnboardingScreen mayor) mayor.reloadUi();
                else if(client.screen instanceof StockScreen stock) stock.reloadUi();
                else if(client.screen instanceof ManagementScreen management) management.reloadUi();
            });
        });
    }
}

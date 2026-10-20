package dev.livingkingdoms.client.citizenvisual;

import dev.livingkingdoms.citizenvisual.*;
import dev.livingkingdoms.profession.domain.ProfessionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/** No common bootstrap references this class: register only from VillageClient. */
public final class CitizenVisualClient {
    /** Activity is null when the citizen has nothing to show (inactive or unassigned). */
    static record View(CitizenPresentation data, Component name, Component secondary, Component activity,
                       ResourceLocation texture, ResourceLocation argonTexture, Language language) {}
    private static final CitizenPresentationCache CACHE = new CitizenPresentationCache(CitizenPresentationCache.MAX_CAPACITY, 400);
    private static final LinkedHashMap<UUID, View> VIEWS = new LinkedHashMap<>();
    private static ClientLevel world;
    private static CitizenHumanoidRenderer renderer;
    private CitizenVisualClient() {}

    public static void register(IEventBus bus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, CitizenVisualConfig.SPEC,
                "livingkingdoms-citizen-visuals.toml");
        bus.addListener(CitizenVisualClient::layers);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, CitizenVisualClient::render);
        NeoForge.EVENT_BUS.addListener(CitizenVisualClient::logout);
        NeoForge.EVENT_BUS.addListener(CitizenModelCommands::register);
        NeoForge.EVENT_BUS.addListener(CitizenVisualClient::leave);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CitizenVisualClient::interact);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CitizenVisualClient::interactSpecific);
        CitizenVisualPayloads.receiver = CitizenVisualClient::receive;
        CitizenVisualPayloads.removeReceiver = packet -> { CACHE.remove(packet.entityUuid()); VIEWS.remove(packet.entityUuid()); };
    }
    private static void layers(EntityRenderersEvent.AddLayers event) { renderer = new CitizenHumanoidRenderer(event.getContext()); }
    private static void resetWorld() {
        var current = Minecraft.getInstance().level;
        if (world != current) { CACHE.clear(); VIEWS.clear(); world = current; }
    }
    public static CitizenPresentationCache.Entry get(Villager entity) {
        resetWorld();
        return world == null ? null : CACHE.get(entity.getId(), entity.getUUID(), world.getGameTime());
    }
    static View view(CitizenPresentation data) {
        View result = VIEWS.get(data.entityUuid());
        if (result == null || !result.data.equals(data) || result.language != Language.getInstance()) {
            result = new View(data, Component.literal(data.name()), Component.translatable("citizen.livingkingdoms.label",
                    profession(data.profession()), data.level()), activity(data), ResourceLocation.fromNamespaceAndPath("livingkingdoms",
                    "textures/entity/citizen/" + data.profession().name().toLowerCase(Locale.ROOT) + "_" + data.skinVariant() + ".png"),
                    ResourceLocation.fromNamespaceAndPath("livingkingdoms", "textures/entity/citizen_argon4/"
                            + data.profession().name().toLowerCase(Locale.ROOT) + "_" + data.skinVariant() + ".png"),
                    Language.getInstance());
            VIEWS.remove(data.entityUuid()); VIEWS.put(data.entityUuid(), result);
            if (VIEWS.size() > CitizenPresentationCache.MAX_CAPACITY) VIEWS.pollFirstEntry();
        }
        return result;
    }
    /** Built once per changed snapshot or language; the renderer only draws the cached component. */
    static Component activity(CitizenPresentation data) {
        if (!data.active()) return null;
        if (data.travelling()) return Component.translatable("citizen.livingkingdoms.doing.travelling");
        String profession = data.profession().name().toLowerCase(Locale.ROOT);
        return switch (data.state()) {
            case WORKING -> data.profession() == ProfessionType.BUILDER
                    ? data.target().isEmpty() ? Component.translatable("citizen.livingkingdoms.doing.builder_idle")
                    : Component.translatable("citizen.livingkingdoms.doing.builder", building(data.target()))
                    : Component.translatable("citizen.livingkingdoms.doing." + profession);
            case REPAIRING -> data.target().isEmpty() ? Component.translatable("work.livingkingdoms.repairing")
                    : Component.translatable("citizen.livingkingdoms.doing.repairing", building(data.target()));
            default -> Component.translatable("work.livingkingdoms." + data.state().name().toLowerCase(Locale.ROOT));
        };
    }
    private static Component building(String kind) {
        String key = kind.startsWith("house_") ? "house" : kind;
        return Component.translatableWithFallback("construction.livingkingdoms.building." + key, kind.replace('_', ' '));
    }
    public static Component profession(ProfessionType type) {
        return Component.translatable("profession.livingkingdoms." + type.name().toLowerCase(Locale.ROOT));
    }
    private static void receive(CitizenVisualPayloads.Snapshot packet) {
        resetWorld(); if (world == null) return;
        CACHE.put(packet.data(), world.getGameTime(), packet.workPulse()); view(packet.data());
        var client = Minecraft.getInstance();
        if (packet.openScreen()) client.setScreen(new CitizenActivityScreen(packet.data()));
        else if (client.screen instanceof CitizenActivityScreen screen && screen.matches(packet.data())) screen.update(packet.data());
    }
    private static void render(RenderLivingEvent.Pre<?, ?> event) {
        if (renderer == null || event.getRenderer() == renderer || !CitizenVisualConfig.HUMANOIDS.get()
                || !(event.getEntity() instanceof Villager villager)) return;
        var entry = get(villager); if (entry == null) return;
        event.setCanceled(true);
        renderer.present(view(entry.data()), entry.actionAt());
        renderer.render(villager, villager.getYRot(), event.getPartialTick(), event.getPoseStack(),
                event.getMultiBufferSource(), event.getPackedLight());
    }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { CACHE.clear(); VIEWS.clear(); world = null; }
    private static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) { CACHE.remove(event.getEntity().getUUID()); VIEWS.remove(event.getEntity().getUUID()); }
    }
    private static boolean handled(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide() || !(event.getTarget() instanceof Villager villager)) return false;
        var entry = get(villager);
        return entry != null && (CitizenVisualService.inspection(entry.data().profession(), event.getEntity().isShiftKeyDown())
                || entry.data().profession() == ProfessionType.MERCHANT);
    }
    private static void interact(PlayerInteractEvent.EntityInteract event) {
        if (handled(event)) { event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS); }
    }
    private static void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!event.getLevel().isClientSide() || !(event.getTarget() instanceof Villager villager)) return;
        var entry = get(villager);
        if (entry != null && (CitizenVisualService.inspection(entry.data().profession(), event.getEntity().isShiftKeyDown())
                || entry.data().profession() == ProfessionType.MERCHANT)) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.PASS);
        }
    }
}

package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.domain.CitizenRole;
import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/** Only entity-local indexed lookups, no world scans, chunk requests or gameplay mutations. */
public final class CitizenVisualService {
    private static final int CAPACITY = 4096;
    private record Sent(CitizenPresentation data, long tick, long soundAt) {}
    private static final LinkedHashMap<UUID, Sent> SENT = new LinkedHashMap<>();
    private CitizenVisualService() {}

    public static void register(IEventBus bus) {
        bus.addListener(CitizenVisualService::tracking);
        bus.addListener(CitizenVisualService::stopTracking);
        bus.addListener(CitizenVisualService::tick);
        bus.addListener(CitizenVisualService::leave);
        bus.addListener(CitizenVisualService::stopped);
        bus.addListener(EventPriority.HIGH, CitizenVisualService::interact);
        bus.addListener(EventPriority.HIGH, CitizenVisualService::interactSpecific);
    }

    public static CitizenPresentation snapshot(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) return null;
        var citizen = CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID()).orElse(null);
        if (citizen == null || citizen.state() != CitizenState.ACTIVE || !CitizenService.canApply(citizen, villager)) return null;
        var jobs = ProfessionSavedData.get(level.getServer());
        var profession = jobs.profession(citizen.id()).orElse(null);
        var type = citizen.role() == CitizenRole.MAYOR ? ProfessionType.MAYOR
                : profession == null ? ProfessionType.UNASSIGNED : profession.type();
        var state = villager.isSleeping() ? WorkState.SLEEPING
                : profession == null || !profession.active() ? WorkState.IDLE : profession.workState();
        String workplace = profession == null || profession.workplaceId() == null ? ""
                : jobs.building(profession.workplaceId()).map(b -> b.kind().name().toLowerCase(Locale.ROOT)).orElse("");
        return new CitizenPresentation(villager.getId(), villager.getUUID(), citizen.name(), citizen.level().value(),
                type, profession == null ? 1 : profession.level().value(), state,
                profession != null && profession.active(), !villager.getNavigation().isDone(), citizen.homeId() != null,
                workplace, profession == null ? 0 : profession.experience());
    }

    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof Villager villager) {
            var data = snapshot(villager);
            if (data != null) PacketDistributor.sendToPlayer(player, new CitizenVisualPayloads.Snapshot(data, false, false));
        }
    }
    public static void stopTracking(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof Villager villager)
            PacketDistributor.sendToPlayer(player, new CitizenVisualPayloads.Remove(villager.getUUID()));
    }
    public static void tick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)
                || Math.floorMod(villager.tickCount + villager.getId(), 20) != 0
                || !villager.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) return;
        var data = snapshot(villager);
        var old = SENT.get(villager.getUUID());
        if (data == null) {
            if (SENT.remove(villager.getUUID()) != null)
                PacketDistributor.sendToPlayersTrackingEntity(villager, new CitizenVisualPayloads.Remove(villager.getUUID()));
            return;
        }
        long now = level.getServer().overworld().getGameTime();
        if (old != null && old.data.equals(data) && now - old.tick < 200) return;
        boolean pulse = data.performedWorkSince(old == null ? null : old.data);
        long soundAt = old == null ? now : old.soundAt;
        if (pulse && now - soundAt >= 40) {
            SoundEvent sound = workSound(data.profession());
            if (sound != null) level.playSound(null, villager.blockPosition(), sound, SoundSource.NEUTRAL, .22F, 1F);
            soundAt = now;
        }
        SENT.remove(villager.getUUID()); SENT.put(villager.getUUID(), new Sent(data, now, soundAt));
        if (SENT.size() > CAPACITY) SENT.pollFirstEntry();
        PacketDistributor.sendToPlayersTrackingEntity(villager, new CitizenVisualPayloads.Snapshot(data, pulse, false));
    }
    private static SoundEvent workSound(ProfessionType type) {
        return switch (type) {
            case FARMER -> SoundEvents.CROP_BREAK;
            case BLACKSMITH -> SoundEvents.ANVIL_USE;
            case LUMBERJACK -> SoundEvents.WOOD_HIT;
            case MINER -> SoundEvents.STONE_HIT;
            case BUILDER -> SoundEvents.WOOD_PLACE;
            default -> null;
        };
    }
    public static void leave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) SENT.remove(event.getEntity().getUUID());
    }
    public static void stopped(ServerStoppedEvent event) { SENT.clear(); }

    /** Normal mayor/merchant clicks keep their existing service. Sneaking inspects any citizen. */
    public static boolean inspection(ProfessionType profession, boolean sneaking) {
        return sneaking || profession != ProfessionType.MAYOR && profession != ProfessionType.MERCHANT;
    }
    public static void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof Villager villager) || !(event.getEntity() instanceof ServerPlayer)) return;
        var data = snapshot(villager);
        if (data != null && inspection(data.profession(), event.getEntity().isShiftKeyDown())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.PASS);
        }
    }
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager) || !(event.getEntity() instanceof ServerPlayer player)) return;
        var data = snapshot(villager);
        if (data == null || !inspection(data.profession(), player.isShiftKeyDown())) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.CONSUME);
        if (event.getHand() != InteractionHand.MAIN_HAND || !villager.isAlive()
                || player.distanceToSqr(villager) > 36 || !player.hasLineOfSight(villager)) return;
        dev.livingkingdoms.quest.expansion.ExpandedQuestService.meetCitizen(player, villager);
        PacketDistributor.sendToPlayer(player, new CitizenVisualPayloads.Snapshot(data, false, true));
    }
}

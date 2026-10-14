package dev.livingkingdoms.chronicle;

import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.onboarding.domain.OnboardingStage;
import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Event-driven persistence then live delivery. Login and data reload never deliver old history. */
public final class ChronicleService {
    private ChronicleService() {}
    public static boolean record(MinecraftServer server, UUID settlement, ChronicleEvent.Kind kind, String receipt, String subject) {
        if (!server.isSameThread()) throw new IllegalStateException("Chronicle requires server authority");
        if (SettlementSavedData.get(server).get(settlement).isEmpty()) return false;
        var event = new ChronicleEvent(UUID.randomUUID(), settlement, kind, server.overworld().getGameTime(), receipt, subject);
        if (!ChronicleSavedData.get(server).record(event)) return false;
        return true;
    }
    public static void send(MinecraftServer server, ChronicleEvent event) {
        if (!server.isSameThread()) throw new IllegalStateException("Chronicle requires server authority");
        for (var player : server.getPlayerList().getPlayers()) deliver(player, event);
    }
    /** Shared kingdoms notify their currently online joined members, never unrelated visitors. */
    public static void deliver(ServerPlayer player, ChronicleEvent event) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Chronicle requires server authority");
        try {
            if (KingdomOnboardingSavedData.get(player.server).stage(player.getUUID(), event.settlement()) != OnboardingStage.JOINED) return;
            String name = SettlementSavedData.get(player.server).get(event.settlement()).map(dev.livingkingdoms.ui.VillageNames::display).orElse("");
            PacketDistributor.sendToPlayer(player, new ChroniclePayloads.Notice(event, name));
        }
        catch (RuntimeException unavailable) { com.mojang.logging.LogUtils.getLogger().warn("Could not deliver kingdom alert to {}; chronicle fact is retained", player.getUUID(), unavailable); }
    }
    /** UI snapshot is bounded and newest first; reading it has no notification side effects. */
    public static CompoundTag history(MinecraftServer server, UUID settlement) {
        var tag = new CompoundTag(); var entries = new ListTag(); var events = ChronicleSavedData.get(server).history(settlement);
        for (int i = events.size() - 1; i >= 0; i--) {
            var entry = ChronicleSavedData.eventTag(events.get(i)); entry.remove("receipt"); entries.add(entry);
        }
        tag.put("entries", entries); return tag;
    }
    /** Capture guard status before profession retirement; call only after the DEAD record committed. */
    public static boolean citizenDied(MinecraftServer server, Citizen before, boolean wasGuard) {
        var current = CitizenSavedData.get(server).citizen(before.id());
        if (before.state() != CitizenState.ACTIVE || current.isEmpty() || current.get().state() != CitizenState.DEAD
                || !current.get().entityId().equals(before.entityId()) || !current.get().settlementId().equals(before.settlementId())) return false;
        return record(server, before.settlementId(), wasGuard ? ChronicleEvent.Kind.GUARD_DIED : ChronicleEvent.Kind.CITIZEN_DIED,
                before.id().toString(), before.name());
    }
    public static boolean essentialWorkplaceEmpty(MinecraftServer server, UUID settlement, UUID building, String transitionReceipt) {
        var professions = ProfessionSavedData.get(server); var workplace = professions.building(building);
        if (workplace.isEmpty() || !workplace.get().settlementId().equals(settlement) || !workplace.get().active()
                || workplace.get().workplaceSlots() == 0 || professions.workers(building) != 0
                || !Set.of(BuildingKind.FARM, BuildingKind.BARRACKS, BuildingKind.CORE, BuildingKind.TOWN_HALL).contains(workplace.get().kind())) return false;
        return record(server, settlement, ChronicleEvent.Kind.ESSENTIAL_WORKPLACE_EMPTY,
                building + ":" + transitionReceipt, workplace.get().kind().name().toLowerCase(Locale.ROOT));
    }
    public static boolean significantThreat(MinecraftServer server, UUID settlement, UUID threatReceipt, String threatKind) {
        return record(server, settlement, ChronicleEvent.Kind.SIGNIFICANT_THREAT, threatReceipt.toString(), threatKind);
    }
    public static boolean buildingCompleted(MinecraftServer server, UUID settlement, UUID order, BuildingKind kind) {
        return record(server, settlement, ChronicleEvent.Kind.CONSTRUCTION_COMPLETED, order.toString(), kind.name().toLowerCase(Locale.ROOT));
    }
    public static boolean buildingUpgraded(MinecraftServer server, UUID settlement, UUID building, int level) {
        if (level < 2 || level > 100) throw new IllegalArgumentException("Invalid committed building upgrade");
        return record(server, settlement, ChronicleEvent.Kind.UPGRADE_COMPLETED, building + ":" + level, Integer.toString(level));
    }
}

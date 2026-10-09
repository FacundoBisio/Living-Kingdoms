package dev.livingkingdoms.citizen;

import dev.livingkingdoms.citizen.domain.CitizenState;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Entity-local events preserve identity across chunk unloads and free slots only on confirmed death/conversion. */
public final class CitizenEvents {
    private CitizenEvents() {}

    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Villager villager)) return;
        var data = CitizenSavedData.get(level.getServer());
        var citizen = data.byEntity(villager.getUUID());
        if (citizen.isPresent()) {
            var settlement = SettlementSavedData.get(level.getServer()).get(citizen.get().settlementId());
            if (citizen.get().state() == CitizenState.ACTIVE && settlement.isPresent() && settlement.get().faction().isAllied()
                    && settlement.get().territory().dimension().equals(level.dimension().location().toString()))
                if (CitizenService.canApply(citizen.get(),villager)) CitizenService.apply(citizen.get(),villager);
            return;
        }
        var identity = NpcIdentity.read(villager);
        if (identity.isEmpty() || (identity.get().role() != NpcRole.MAYOR && identity.get().role() != NpcRole.RESIDENT)) return;
        var settlements = SettlementSavedData.get(level.getServer());
        var settlement = settlements.get(identity.get().settlementId());
        if (settlement.isEmpty() || !settlement.get().faction().isAllied()
                || !settlement.get().territory().dimension().equals(level.dimension().location().toString())) return;
        // Establishment may still roll back its NPC entities: its post-commit hook initializes this flag.
        if (!data.initialized(settlement.get().id()) || villager.getPersistentData().contains(CitizenService.CITIZEN_ID_KEY)) return;
        CitizenService.registerManaged(level,settlement.get(),villager);
        CitizenService.refreshHomes(level,settlement.get());
    }

    public static void onDeath(LivingDeathEvent event) {
        if (!event.isCanceled() && event.getEntity().level() instanceof ServerLevel level)
            retire(level,event.getEntity().getUUID());
    }

    public static void onConversion(LivingConversionEvent.Post event) {
        if (event.getEntity() instanceof Villager && !(event.getOutcome() instanceof Villager)
                && event.getEntity().level() instanceof ServerLevel level)
            retire(level,event.getEntity().getUUID());
    }

    private static void retire(ServerLevel level, java.util.UUID entityId) {
        var data = CitizenSavedData.get(level.getServer());
        var citizen = data.byEntity(entityId);
        if (citizen.isEmpty() || !data.markDead(entityId)) return;
        SettlementSavedData.get(level.getServer()).get(citizen.get().settlementId())
                .filter(s -> s.faction().isAllied() && s.territory().dimension().equals(level.dimension().location().toString()))
                .ifPresent(s -> CitizenService.refreshHomes(level,s));
    }

    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.tickCount % 40 != 0
                || !(villager.level() instanceof ServerLevel level)
                || !villager.getPersistentData().hasUUID(CitizenService.CITIZEN_ID_KEY)) return;
        CitizenSavedData.get(level.getServer()).byEntity(villager.getUUID())
                .filter(c -> c.state() == CitizenState.ACTIVE
                        && c.id().equals(villager.getPersistentData().getUUID(CitizenService.CITIZEN_ID_KEY))
                        && CitizenService.canApply(c,villager))
                .ifPresent(c -> { if(!dev.livingkingdoms.profession.FarmerWork.assigned(villager)) CitizenService.guideHome(villager); });
    }
}

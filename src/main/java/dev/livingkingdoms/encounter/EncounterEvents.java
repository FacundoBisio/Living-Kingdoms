package dev.livingkingdoms.encounter;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.NearestAlliedSettlementService;
import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

import java.util.Optional;

/** Local death/conversion events; there is no entity polling or encounter tick loop. */
public final class EncounterEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private EncounterEvents() {}

    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)
                || !event.getEntity().isDeadOrDying()) return;
        var identity = EncounterMember.read(event.getEntity());
        if (identity.isEmpty()) return; // Unrelated vanilla deaths do not even load the encounter store.
        EncounterSavedData parties = EncounterSavedData.get(level.getServer());
        var tracked = parties.forMember(event.getEntity().getUUID());
        if (tracked.isEmpty() || !tracked.get().id().equals(identity.get().partyId())
                || tracked.get().faction() != identity.get().faction()) return;
        var defeated = parties.recordDeath(event.getEntity().getUUID());
        if (defeated.isEmpty()) return;
        HostileParty party = defeated.orElseThrow();
        // Default debug parties are combat fixtures, not repeatable reputation generators.
        if (!party.rewardEligible() || party.reputationReward() == 0
                || !(event.getSource().getEntity() instanceof ServerPlayer killer)
                || killer.isSpectator() || killer.serverLevel() != level) return;
        BlockPos position = event.getEntity().blockPosition();
        int range = KingdomConfig.ENCOUNTER_REPUTATION_RANGE.get();
        Optional<Settlement> settlement = relevantSettlement(level, party, position, range);
        if (settlement.isEmpty()) return;
        try {
            QuestSavedData reputation = QuestSavedData.get(level.getServer());
            Settlement allied = settlement.orElseThrow();
            if (reputation.awardEncounterReputationOnce(party.id(), killer.getUUID(), allied.id(), party.reputationReward())) {
                killer.displayClientMessage(Component.translatable("encounter.livingkingdoms.reputation_awarded",
                        Component.translatable("encounter.livingkingdoms.type." + party.type().id()),
                        party.reputationReward(), allied.name()), false);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Could not award reputation for defeated party {}; no reward replay is attempted", party.id(), failure);
        }
    }

    private static Optional<Settlement> relevantSettlement(ServerLevel level, HostileParty party, BlockPos position, int range) {
        if (party.associatedSettlementId() != null) {
            var associated = NearestAlliedSettlementService.findAlliedById(level, party.associatedSettlementId());
            if (associated.isPresent()) {
                var territory = associated.get().territory();
                double dx = (double) territory.x() - position.getX();
                double dz = (double) territory.z() - position.getZ();
                if (dx * dx + dz * dz <= (double) range * range) return associated;
            }
        }
        return NearestAlliedSettlementService.findNearest(level, position, range);
    }

    public static void onConversion(LivingConversionEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        var identity = EncounterMember.read(event.getEntity());
        if (identity.isEmpty()) return;
        EncounterSavedData parties = EncounterSavedData.get(level.getServer());
        var tracked = parties.forMember(event.getEntity().getUUID());
        if (tracked.isEmpty() || !tracked.get().id().equals(identity.get().partyId())
                || tracked.get().faction() != identity.get().faction()) return;
        if (parties.replaceMember(event.getEntity().getUUID(), event.getOutcome().getUUID())) {
            EncounterMember.attach(event.getOutcome(), identity.get().partyId(), identity.get().faction());
            if (event.getOutcome() instanceof Mob mob) mob.setPersistenceRequired();
        }
    }
}

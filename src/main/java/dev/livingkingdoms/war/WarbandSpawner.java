package dev.livingkingdoms.war;

import dev.livingkingdoms.config.DefenseConfig;
import dev.livingkingdoms.config.WarConfig;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.ThreatApproachPolicy;
import dev.livingkingdoms.encounter.EncounterDefinition;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.war.domain.WarPolicy;
import dev.livingkingdoms.war.persistence.WarSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Places warbands through the shared encounter spawner. At most 24 loaded, validated proposals; never loads chunks. */
public final class WarbandSpawner {
    private WarbandSpawner() {}

    /**
     * A raid marching on {@code target} from the side of {@code attacker}. It is linked to the target's
     * threat flow (warning, guards, Local Defense quest) exactly like a natural approaching party.
     */
    public static Optional<HostileParty> raid(ServerLevel level, Settlement target, Settlement attacker, int strength) {
        int min = Math.max(DefenseConfig.APPROACH_MIN_DISTANCE.get() + 6, target.territory().radius() + 7);
        int max = DefenseConfig.APPROACH_MAX_DISTANCE.get() - 6;
        if (min > max) return Optional.empty();
        EncounterDefinition definition = EncounterDefinition.warband(WarPolicy.warbandSize(WarConfig.WARBAND_SIZE.get(), strength));
        String dimension = level.dimension().location().toString();
        double bearing = WarPolicy.bearing(target.territory().x(), target.territory().z(), attacker.territory().x(), attacker.territory().z());
        for (double angle : WarPolicy.approachAngles(bearing)) {
            for (int radius : new int[]{min, (min + max) / 2, max}) {
                BlockPos origin = new BlockPos(target.territory().x() + (int) Math.round(Math.cos(angle) * radius), target.territory().y(),
                        target.territory().z() + (int) Math.round(Math.sin(angle) * radius));
                if (!ThreatApproachPolicy.validOrigin(Math.hypot(origin.getX() - target.territory().x(), origin.getZ() - target.territory().z()),
                        target.territory().radius(), DefenseConfig.APPROACH_MIN_DISTANCE.get(), DefenseConfig.APPROACH_MAX_DISTANCE.get())
                        || SettlementSavedData.get(level.getServer()).nearAlliedTerritory(dimension, origin.getX(), origin.getZ(), 6)
                        || !level.getChunkSource().hasChunk(origin.getX() >> 4, origin.getZ() >> 4)) continue;
                var result = EncounterSpawner.spawnAt(level, origin, definition, false, true);
                if (!result.successful()) continue;
                if (DefenseService.beginApproach(level, target, result.party()).isPresent()) return Optional.of(result.party());
                discard(level, result.party());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The hostile kingdom's home guard, fielded around a trespassing player at most once per cooldown. */
    public static Optional<HostileParty> garrison(ServerLevel level, Settlement kingdom, ServerPlayer trespasser, long now) {
        var server = level.getServer();
        WarSavedData data = WarSavedData.get(server);
        var front = data.front(kingdom.id()).orElse(null);
        if (front == null || !front.garrisonReady(now)) return Optional.empty();
        EncounterDefinition definition = EncounterDefinition.warband(WarPolicy.warbandSize(WarConfig.WARBAND_SIZE.get(), front.strength()));
        var result = EncounterSpawner.spawnAround(level, trespasser.blockPosition(), definition, false, true);
        if (!result.successful()) return Optional.empty();
        if (!data.putFront(kingdom.id(), front.garrisonSpawned(result.party().id()))) {
            discard(level, result.party());
            return Optional.empty();
        }
        trespasser.displayClientMessage(Component.translatable("war.livingkingdoms.garrison",
                dev.livingkingdoms.ui.VillageNames.display(kingdom)), false);
        return Optional.of(result.party());
    }

    private static void discard(ServerLevel level, HostileParty party) {
        EncounterSavedData.get(level.getServer()).remove(party.id());
        for (var id : party.remainingMembers()) {
            var entity = level.getEntity(id);
            if (entity != null) entity.discard();
        }
    }
}

package dev.livingkingdoms.encounter;

import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.OriginRegion;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.faction.FactionRelations;
import dev.livingkingdoms.progression.domain.LevelSummary;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only regional activity references for future quest selection; spawning has no quest dependency. */
public final class EncounterQueries {
    private EncounterQueries() {}

    public static List<Reference> activeNear(ServerLevel level, BlockPos position, int radius) {
        return EncounterSavedData.get(level.getServer()).activeNearby(level.dimension().location().toString(),
                position.getX(), position.getZ(), radius).stream().map(party -> reference(level, party)).toList();
    }

    public static Optional<Reference> find(ServerLevel level, UUID encounterId) {
        return EncounterSavedData.get(level.getServer()).get(encounterId)
                .filter(party -> party.origin().dimension().equals(level.dimension().location().toString()))
                .map(party -> reference(level, party));
    }

    private static Reference reference(ServerLevel level, HostileParty party) {
        UUID hostileSettlement = party.associatedSettlementId() == null ? null
                : SettlementSavedData.get(level.getServer()).get(party.associatedSettlementId())
                .filter(settlement -> FactionRelations.isHostile(Faction.ALLIED_KINGDOM, settlement.faction()))
                .map(settlement -> settlement.id()).orElse(null);
        return new Reference(party.id(), party.faction(), party.type(), party.origin(), hostileSettlement, party.threatRating(), party.levels());
    }

    /** A future camp-associated party can expose its hostile source; today's allied reward association is not that source. */
    public record Reference(UUID encounterId, Faction faction, PartyType type, OriginRegion region,
                            UUID hostileSettlementId, int threatRating, LevelSummary levels) {}
}

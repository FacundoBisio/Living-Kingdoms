package dev.livingkingdoms.encounter;

import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;

import java.util.Objects;

/** Content settings are captured at spawn; later config changes do not rewrite a party. */
public record EncounterDefinition(PartyType type, Faction faction, int memberCount,
                                  int threatRating, int reputationReward, boolean captain) {
    public EncounterDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(faction, "faction");
        if (faction != type.faction()) throw new IllegalArgumentException("Encounter faction must match its type");
        if (memberCount < 1 || memberCount > 8) throw new IllegalArgumentException("Encounter size must be between 1 and 8");
        if (threatRating < 1 || threatRating > 100) throw new IllegalArgumentException("Invalid encounter threat rating");
        if (reputationReward < 0 || reputationReward > 1_000_000) throw new IllegalArgumentException("Invalid encounter reward");
        if (captain && type != PartyType.PILLAGER_PATROL) throw new IllegalArgumentException("Only patrols have captains");
    }

    public static EncounterDefinition fromConfig(PartyType type) {
        Objects.requireNonNull(type, "type");
        boolean captain = type == PartyType.PILLAGER_PATROL && KingdomConfig.ENCOUNTER_PATROL_CAPTAIN.get();
        return switch (type) {
            case PILLAGER_PATROL -> new EncounterDefinition(type, type.faction(),
                    KingdomConfig.ENCOUNTER_PILLAGER_SIZE.get(), KingdomConfig.ENCOUNTER_PILLAGER_THREAT.get(),
                    captain ? KingdomConfig.ENCOUNTER_REPUTATION_CAPTAIN.get() : KingdomConfig.ENCOUNTER_REPUTATION_NORMAL.get(), captain);
            case UNDEAD_HORDE -> new EncounterDefinition(type, type.faction(),
                    KingdomConfig.ENCOUNTER_UNDEAD_SIZE.get(), KingdomConfig.ENCOUNTER_UNDEAD_THREAT.get(),
                    KingdomConfig.ENCOUNTER_REPUTATION_NORMAL.get(), false);
        };
    }
}

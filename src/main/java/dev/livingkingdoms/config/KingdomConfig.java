package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world settings, loaded by NeoForge on the server. */
public final class KingdomConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue SETTLEMENT_RADIUS;
    public static final ModConfigSpec.IntValue INITIAL_POPULATION;
    public static final ModConfigSpec.IntValue GENERATION_SEARCH_RANGE;
    public static final ModConfigSpec.IntValue GENERATION_MAX_SLOPE;
    public static final ModConfigSpec.IntValue QUEST_REQUIRED_IRON;
    public static final ModConfigSpec.IntValue QUEST_REWARD_EMERALDS;
    public static final ModConfigSpec.IntValue QUEST_REPUTATION_REWARD;
    public static final ModConfigSpec.IntValue ENCOUNTER_PILLAGER_SIZE;
    public static final ModConfigSpec.IntValue ENCOUNTER_UNDEAD_SIZE;
    public static final ModConfigSpec.IntValue ENCOUNTER_PILLAGER_THREAT;
    public static final ModConfigSpec.IntValue ENCOUNTER_UNDEAD_THREAT;
    public static final ModConfigSpec.IntValue ENCOUNTER_REPUTATION_NORMAL;
    public static final ModConfigSpec.IntValue ENCOUNTER_REPUTATION_CAPTAIN;
    public static final ModConfigSpec.IntValue ENCOUNTER_REPUTATION_RANGE;
    public static final ModConfigSpec.BooleanValue ENCOUNTER_PATROL_CAPTAIN;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("settlements");
        SETTLEMENT_RADIUS = builder.comment("Horizontal territory radius for new debug settlements, in blocks.")
                .defineInRange("radius", 48, 1, 1024);
        INITIAL_POPULATION = builder.comment("Initial abstract population; does not spawn NPC entities.")
                .defineInRange("initialPopulation", 5, 0, 10000);
        builder.pop();
        builder.push("generation");
        GENERATION_SEARCH_RANGE = builder.comment("Maximum horizontal search distance for manual generation; loaded chunks only.")
                .defineInRange("searchRange", 64, 32, 128);
        GENERATION_MAX_SLOPE = builder.comment("Maximum surface height difference allowed under a settlement; no excavation.")
                .defineInRange("maxTerrainVariation", 2, 0, 3);
        builder.pop();
        builder.push("quests");
        QUEST_REQUIRED_IRON = builder.comment("Iron ingots required for newly accepted Iron Shortage quests.")
                .defineInRange("requiredIron", 16, 1, 2304);
        QUEST_REWARD_EMERALDS = builder.comment("Emerald reward for newly accepted quests; delivery requires inventory space.")
                .defineInRange("rewardEmeralds", 8, 1, 2304);
        QUEST_REPUTATION_REWARD = builder.comment("Settlement reputation reward for newly accepted quests.")
                .defineInRange("reputationReward", 10, 1, 1000000);
        builder.pop();
        builder.push("encounters");
        ENCOUNTER_PILLAGER_SIZE = builder.comment("Members in newly spawned Pillager patrols.")
                .defineInRange("pillagerPatrolSize", 4, 3, 5);
        ENCOUNTER_UNDEAD_SIZE = builder.comment("Members in newly spawned mixed Zombie/Skeleton hordes.")
                .defineInRange("undeadHordeSize", 6, 4, 8);
        ENCOUNTER_PILLAGER_THREAT = builder.comment("Faction-independent threat rating for new Pillager patrols; metadata only.")
                .defineInRange("pillagerThreat", 2, 1, 100);
        ENCOUNTER_UNDEAD_THREAT = builder.comment("Faction-independent threat rating for new Undead hordes; metadata only.")
                .defineInRange("undeadThreat", 3, 1, 100);
        ENCOUNTER_PATROL_CAPTAIN = builder.comment("Include one vanilla Captain in newly spawned Pillager patrols.")
                .define("patrolCaptain", true);
        ENCOUNTER_REPUTATION_NORMAL = builder.comment("Reputation for eligible parties without a Captain; snapshotted on spawn, 0 disables.")
                .defineInRange("normalReputation", 2, 0, 1000000);
        ENCOUNTER_REPUTATION_CAPTAIN = builder.comment("Total reputation for eligible Captain parties, rather than an additional per-member reward.")
                .defineInRange("captainReputation", 4, 0, 1000000);
        ENCOUNTER_REPUTATION_RANGE = builder.comment("Maximum horizontal distance from the final defeat to an allied settlement center.")
                .defineInRange("reputationRange", 256, 0, 4096);
        builder.pop();
        SPEC = builder.build();
    }

    private KingdomConfig() {}
}

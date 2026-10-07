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
    public static final ModConfigSpec.BooleanValue NATURAL_ENABLED;
    public static final ModConfigSpec.IntValue NATURAL_CHECK_INTERVAL;
    public static final ModConfigSpec.IntValue NATURAL_COOLDOWN;
    public static final ModConfigSpec.IntValue NATURAL_MIN_DISTANCE;
    public static final ModConfigSpec.IntValue NATURAL_MAX_NEARBY;
    public static final ModConfigSpec.IntValue NATURAL_REGION_RADIUS;
    public static final ModConfigSpec.IntValue NATURAL_MIN_PLAYER_DISTANCE;
    public static final ModConfigSpec.IntValue NATURAL_MAX_PLAYER_DISTANCE;
    public static final ModConfigSpec.IntValue NATURAL_MAX_TRACKED;
    public static final ModConfigSpec.IntValue ENCOUNTER_ACTIVE_LIFETIME;
    public static final ModConfigSpec.IntValue ENCOUNTER_COMPLETED_RETENTION;
    public static final ModConfigSpec.DoubleValue ENCOUNTER_MIN_CONTRIBUTION;

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
        ENCOUNTER_MIN_CONTRIBUTION = builder.comment("Accumulated post-reduction damage score required per player, with a hit in the last five minutes. Each hit is capped at the member's maximum health; 4 damage = 2 hearts.")
                .defineInRange("minimumContributionDamage", 4.0, 1.0, 1000.0);
        ENCOUNTER_ACTIVE_LIFETIME = builder.comment("Maximum tracked lifetime in server ticks; expiration abandons the party without rewards and retires its members.")
                .defineInRange("activeLifetimeTicks", 72000, 1200, 1728000);
        ENCOUNTER_COMPLETED_RETENTION = builder.comment("Keep defeated metadata/receipts this many server ticks for inspection, then remove them. Reputation remains.")
                .defineInRange("completedRetentionTicks", 1200, 200, 72000);
        builder.push("natural");
        NATURAL_ENABLED = builder.comment("Conservative natural encounters in active Overworld wilderness. Does not replace vanilla spawning.")
                .define("enabled", true);
        NATURAL_CHECK_INTERVAL = builder.comment("Ticks between at most one player selection and three local candidate checks per dimension.")
                .defineInRange("checkIntervalTicks", 200, 100, 24000);
        NATURAL_COOLDOWN = builder.comment("Persistent dimension-wide cooldown after every attempt, successful or not.")
                .defineInRange("cooldownTicks", 2400, 600, 72000);
        NATURAL_MIN_DISTANCE = builder.comment("Minimum separation between tracked active encounter origins, in blocks.")
                .defineInRange("minimumEncounterDistance", 128, 32, 512);
        NATURAL_MAX_NEARBY = builder.comment("Maximum tracked active groups around the selected player and proposed region.")
                .defineInRange("maximumNearbyGroups", 2, 1, 8);
        NATURAL_REGION_RADIUS = builder.comment("Radius for nearby group limits, using persisted origin metadata.")
                .defineInRange("regionRadius", 192, 64, 1024);
        NATURAL_MIN_PLAYER_DISTANCE = builder.comment("Minimum group member distance from players. Must not exceed maximumSpawnDistance.")
                .defineInRange("minimumPlayerDistance", 48, 24, 128);
        NATURAL_MAX_PLAYER_DISTANCE = builder.comment("Maximum candidate-center distance from the selected player; actual chunks must be entity-ticking.")
                .defineInRange("maximumSpawnDistance", 80, 32, 160);
        NATURAL_MAX_TRACKED = builder.comment("Global tracked party cap before natural spawning stops, including debug, unloaded and defeated records awaiting cleanup.")
                .defineInRange("maximumTrackedParties", 256, 8, 1024);
        builder.pop();
        builder.pop();
        ProgressionConfig.define(builder);
        SPEC = builder.build();
    }

    private KingdomConfig() {}
}

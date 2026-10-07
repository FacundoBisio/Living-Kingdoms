package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world settings, loaded by NeoForge on the server. */
public final class KingdomConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue SETTLEMENT_RADIUS;
    public static final ModConfigSpec.IntValue INITIAL_POPULATION;
    public static final ModConfigSpec.IntValue GENERATION_SEARCH_RANGE;
    public static final ModConfigSpec.IntValue GENERATION_MAX_SLOPE;

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
        SPEC = builder.build();
    }

    private KingdomConfig() {}
}

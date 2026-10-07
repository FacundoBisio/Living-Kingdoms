package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world settings, loaded by NeoForge on the server. */
public final class KingdomConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue SETTLEMENT_RADIUS;
    public static final ModConfigSpec.IntValue INITIAL_POPULATION;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("settlements");
        SETTLEMENT_RADIUS = builder.comment("Horizontal territory radius for new debug settlements, in blocks.")
                .defineInRange("radius", 48, 1, 1024);
        INITIAL_POPULATION = builder.comment("Initial abstract population; does not spawn NPC entities.")
                .defineInRange("initialPopulation", 5, 0, 10000);
        builder.pop();
        SPEC = builder.build();
    }

    private KingdomConfig() {}
}

package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** [generation.natural]: independent NPC kingdoms placed while players explore new terrain. */
public final class NpcKingdomConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.IntValue CELL_SIZE;
    public static ModConfigSpec.DoubleValue CHANCE;
    public static ModConfigSpec.IntValue MIN_DISTANCE;
    public static ModConfigSpec.IntValue CHECK_INTERVAL;
    public static ModConfigSpec.IntValue COOLDOWN;
    public static ModConfigSpec.IntValue MAX_KINGDOMS;

    private NpcKingdomConfig() {}

    static void define(ModConfigSpec.Builder builder) {
        builder.comment("Independent NPC kingdoms generated in newly created Overworld chunks only. Existing terrain is never",
                "rescanned, chunks are never loaded or force-loaded, and at most one village is attempted per check interval.",
                "A successful placement writes roughly 5k-15k blocks in a single server tick.").push("natural");
        ENABLED = builder.comment("Generate independent NPC kingdoms while players explore new terrain.")
                .define("enabled", true);
        CELL_SIZE = builder.comment("Side of the square world region that may hold at most one natural NPC kingdom, in blocks.",
                        "Changing it re-partitions the world; already attempted cells are tracked under the old size.")
                .defineInRange("cellSize", 768, 256, 4096);
        CHANCE = builder.comment("Seeded chance that a region cell receives an NPC kingdom attempt.")
                .defineInRange("chance", 0.35, 0.0, 1.0);
        MIN_DISTANCE = builder.comment("Minimum distance from any existing settlement territory to a new NPC kingdom center, in blocks.")
                .defineInRange("minDistance", 384, 128, 4096);
        CHECK_INTERVAL = builder.comment("Server ticks between checks; each check attempts at most one queued region cell.")
                .defineInRange("checkIntervalTicks", 200, 20, 24000);
        COOLDOWN = builder.comment("Persistent server ticks to wait after a successful natural NPC kingdom.")
                .defineInRange("cooldownTicks", 6000, 0, 1728000);
        MAX_KINGDOMS = builder.comment("Maximum registered NPC kingdoms per world before natural generation stops; 0 disables it.")
                .defineInRange("maxNpcKingdoms", 48, 0, 1024);
        builder.pop();
    }
}

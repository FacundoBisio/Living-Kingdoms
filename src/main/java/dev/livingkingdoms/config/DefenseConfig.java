package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class DefenseConfig {
    public static ModConfigSpec.IntValue EVENT_DURATION, DETECTION_INTERVAL, SECURITY_DETECTION_BONUS,
            WATCHTOWER_DETECTION_BONUS, MAX_DETECTION_RANGE, RETENTION;
    private DefenseConfig() {}
    public static void define(ModConfigSpec.Builder builder) {
        builder.push("defense");
        EVENT_DURATION = builder.comment("A local threat expires without destructive settlement damage.")
                .defineInRange("eventDurationTicks", 12000, 20, 1728000);
        DETECTION_INTERVAL = builder.defineInRange("detectionIntervalTicks", 80, 20, 400);
        SECURITY_DETECTION_BONUS = builder.comment("Maximum extra detection distance at Security 100.")
                .defineInRange("securityDetectionBonus", 4, 0, 8);
        WATCHTOWER_DETECTION_BONUS = builder.comment("An active Watchtower adds detection distance; multiple towers do not stack.")
                .defineInRange("watchtowerDetectionBonus", 12, 0, 24);
        MAX_DETECTION_RANGE = builder.defineInRange("maximumDetectionRange", 72, 8, 96);
        RETENTION = builder.comment("Terminal event metadata may be pruned after this age only when its party is gone and no live quest refers to it.")
                .defineInRange("terminalRetentionTicks", 168000, 24000, 17280000);
        builder.pop();
    }
}

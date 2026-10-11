package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class DefenseConfig {
    public static ModConfigSpec.IntValue EVENT_DURATION, DETECTION_INTERVAL, SECURITY_DETECTION_BONUS,
            WATCHTOWER_DETECTION_BONUS, MAX_DETECTION_RANGE, RETENTION, NORMAL_DETECTION_RANGE,
            APPROACH_MIN_DISTANCE, APPROACH_MAX_DISTANCE, OBJECTIVE_MARGIN, ROUTE_FAILURE_LIMIT;
    public static ModConfigSpec.DoubleValue APPROACH_SPEED;
    private DefenseConfig() {}
    public static void define(ModConfigSpec.Builder builder) {
        builder.push("defense");
        EVENT_DURATION = builder.comment("A local threat expires without destructive settlement damage.")
                .defineInRange("eventDurationTicks", 12000, 20, 1728000);
        DETECTION_INTERVAL = builder.defineInRange("detectionIntervalTicks", 80, 20, 400);
        SECURITY_DETECTION_BONUS = builder.comment("Maximum extra detection distance at Security 100.")
                .defineInRange("securityDetectionBonus", 4, 0, 8);
        WATCHTOWER_DETECTION_BONUS = builder.comment("An active Watchtower adds detection distance; multiple towers do not stack.")
                .defineInRange("watchtowerDetectionBonus", 40, 0, 128);
        NORMAL_DETECTION_RANGE=builder.defineInRange("normalDetectionRange",60,8,512);
        MAX_DETECTION_RANGE = builder.defineInRange("maximumDetectionRange", 160, 8, 1024);
        APPROACH_MIN_DISTANCE=builder.defineInRange("minimumApproachDistance",70,16,4096);
        APPROACH_MAX_DISTANCE=builder.defineInRange("maximumApproachDistance",140,16,4096);
        APPROACH_SPEED=builder.defineInRange("approachMovementSpeed",.65,.1,1.5);
        OBJECTIVE_MARGIN=builder.defineInRange("hostileObjectiveMargin",16,0,64);
        ROUTE_FAILURE_LIMIT=builder.comment("Consecutive loaded-site navigation failures before safe cancellation, with no rewards.")
                .defineInRange("routeFailureLimit",8,3,40);
        RETENTION = builder.comment("Terminal event metadata may be pruned after this age only when its party is gone and no live quest refers to it.")
                .defineInRange("terminalRetentionTicks", 168000, 24000, 17280000);
        builder.pop();
    }
}

package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Read only during a Charter attempt; never drives a background village scan. */
public final class SettlementEstablishmentConfig {
    public static ModConfigSpec.IntValue DETECTION_RADIUS, MIN_VILLAGERS, MIN_BEDS, MAX_CONVERSION_RADIUS;

    static void define(ModConfigSpec.Builder builder) {
        builder.push("establishment");
        DETECTION_RADIUS = builder.comment("Loaded village survey radius in blocks; villagers must also be within 24 vertical blocks.")
                .defineInRange("villageDetectionRadius", 48, 16, 96);
        MIN_VILLAGERS = builder.defineInRange("minimumVillagers", 2, 1, 32);
        MIN_BEDS = builder.comment("Count complete beds from HOME POIs, including unclaimed beds; bell is optional.")
                .defineInRange("minimumBeds", 2, 1, 64);
        MAX_CONVERSION_RADIUS = builder.comment("Maximum territory enclosing the surveyed village and minimal infrastructure.")
                .defineInRange("maximumConversionRadius", 128, 48, 256);
        builder.pop();
    }

    private SettlementEstablishmentConfig() {}
}

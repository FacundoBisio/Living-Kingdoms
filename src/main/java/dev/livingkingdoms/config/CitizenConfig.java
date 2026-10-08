package dev.livingkingdoms.config;

import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Per-world housing and immigration policy. Capacity changes reconcile existing assignments. */
public final class CitizenConfig {
    public static ModConfigSpec.IntValue DEFAULT_HOUSE_CAPACITY;
    public static ModConfigSpec.ConfigValue<List<? extends String>> HOUSE_CAPACITY_OVERRIDES;
    public static ModConfigSpec.BooleanValue INCLUDE_TEMPORARY_SHELTERS;
    public static ModConfigSpec.BooleanValue IMMIGRATION_ENABLED;
    public static ModConfigSpec.IntValue CHECK_INTERVAL;
    public static ModConfigSpec.IntValue MIN_COOLDOWN;
    public static ModConfigSpec.IntValue MAX_COOLDOWN;
    public static ModConfigSpec.IntValue CANDIDATE_LIFETIME;
    public static ModConfigSpec.IntValue MAX_PENDING;
    public static ModConfigSpec.IntValue MIN_FREE_HOUSING;
    public static ModConfigSpec.DoubleValue IMMIGRATION_CHANCE;
    public static ModConfigSpec.IntValue MIN_LEVEL;
    public static ModConfigSpec.IntValue MAX_LEVEL;

    private CitizenConfig() {}

    static void define(ModConfigSpec.Builder builder) {
        builder.push("citizens");
        builder.push("housing");
        DEFAULT_HOUSE_CAPACITY = builder.comment("Default capacity for registered permanent house templates.")
                .defineInRange("defaultCapacity", 2, 1, 128);
        HOUSE_CAPACITY_OVERRIDES = builder.comment("Per-template capacity, e.g. livingkingdoms:allied/plains/house=2. Last matching override wins.")
                .defineListAllowEmpty("templateCapacities", List.of(), CitizenConfig::validOverride);
        INCLUDE_TEMPORARY_SHELTERS = builder.comment("Explicitly include registered founding camps as housing; disabled by default.")
                .define("includeTemporaryShelters", false);
        builder.pop();
        builder.push("immigration");
        IMMIGRATION_ENABLED = builder.define("enabled", true);
        CHECK_INTERVAL = builder.comment("Ticks between lightweight immigration evaluations; no entity scans.")
                .defineInRange("checkIntervalTicks", 200, 100, 24000);
        MIN_COOLDOWN = builder.comment("Minimum persistent cooldown after an immigration opportunity.")
                .defineInRange("minimumCooldownTicks", 24000, 200, 1728000);
        MAX_COOLDOWN = builder.comment("Maximum persistent cooldown; must not be less than the minimum.")
                .defineInRange("maximumCooldownTicks", 72000, 200, 1728000);
        CANDIDATE_LIFETIME = builder.defineInRange("candidateLifetimeTicks", 24000, 200, 1728000);
        MAX_PENDING = builder.defineInRange("maximumPendingCandidates", 2, 1, 16);
        MIN_FREE_HOUSING = builder.defineInRange("minimumFreeHousing", 1, 1, 128);
        IMMIGRATION_CHANCE = builder.comment("Chance per due eligible opportunity; candidates are not guaranteed.")
                .defineInRange("chance", 0.25, 0.0, 1.0);
        MIN_LEVEL = builder.defineInRange("minimumStartingLevel", 1, 1, 100);
        MAX_LEVEL = builder.defineInRange("maximumStartingLevel", 3, 1, 100);
        builder.pop(); builder.pop();
    }

    public static int capacity(SettlementLayoutMetadata.Building building) {
        int result = DEFAULT_HOUSE_CAPACITY.get();
        for (String override : HOUSE_CAPACITY_OVERRIDES.get()) {
            int separator = override.indexOf('=');
            if (override.substring(0, separator).equals(building.template().toString()))
                result = Integer.parseInt(override.substring(separator + 1));
        }
        return result;
    }

    private static boolean validOverride(Object value) {
        if (!(value instanceof String text) || !text.matches("[a-z0-9_.-]+:[a-z0-9/._-]+=\\d{1,3}")) return false;
        int capacity = Integer.parseInt(text.substring(text.indexOf('=') + 1));
        return capacity >= 1 && capacity <= 128;
    }
}

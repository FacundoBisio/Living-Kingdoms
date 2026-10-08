package dev.livingkingdoms.config;

import dev.livingkingdoms.structure.BuildingKind;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/** Bounds are deliberately small: generation is an explicit, loaded-chunk operation. */
public final class SettlementGenerationConfig {
    private static final Map<BuildingKind, Values> PLOTS = new EnumMap<>(BuildingKind.class);
    public static ModConfigSpec.IntValue PLOT_SEARCH_RADIUS;
    public static ModConfigSpec.IntValue PLOT_ATTEMPTS;
    public static ModConfigSpec.IntValue SPACING;
    public static ModConfigSpec.IntValue PATH_NODES;

    private SettlementGenerationConfig() {}

    static void define(ModConfigSpec.Builder builder) {
        PLOT_SEARCH_RADIUS = builder.comment("Maximum building-center distance from the plaza; also constrained by territory.")
                .defineInRange("plotSearchRadius", 30, 16, 48);
        PLOT_ATTEMPTS = builder.comment("Bounded plot candidates per requested building.")
                .defineInRange("plotAttempts", 64, 8, 128);
        SPACING = builder.comment("Clear blocks between building footprints.").defineInRange("buildingSpacing", 2, 1, 4);
        PATH_NODES = builder.comment("Maximum local path search nodes per candidate; no global pathfinding.")
                .defineInRange("pathSearchNodes", 512, 64, 1024);
        for (BuildingKind kind : BuildingKind.values()) {
            builder.push(kind.name().toLowerCase(java.util.Locale.ROOT));
            int slope = switch (kind) {
                case CORE, TOWN_HALL, BARRACKS -> 2;
                default -> 3;
            };
            var variance = builder.comment("Maximum height variance across this individual footprint.")
                    .defineInRange("maxHeightVariance", slope, 0, 4);
            var depth = builder.comment("Required consecutive solid natural ground blocks below every column.")
                    .defineInRange("foundationDepth", 1, 1, 3);
            var support = builder.comment("Maximum blocks of infill above original ground; never excavates hills.")
                    .defineInRange("maxSupportDepth", slope, 0, 4);
            PLOTS.put(kind, new Values(variance, depth, support));
            builder.pop();
        }
    }

    public static Tolerance tolerance(BuildingKind kind, boolean relaxed) {
        Values values = PLOTS.get(kind);
        int variance = kind == BuildingKind.CORE ? KingdomConfig.GENERATION_MAX_SLOPE.get() : values.variance.get();
        // The legacy key remains authoritative for the core, allowing old server configs to load.
        variance = Math.min(variance, values.variance.get());
        return new Tolerance(Math.min(4, variance + (relaxed ? 1 : 0)), values.depth.get(),
                Math.min(4, values.support.get() + (relaxed ? 1 : 0)));
    }

    private record Values(ModConfigSpec.IntValue variance, ModConfigSpec.IntValue depth, ModConfigSpec.IntValue support) {}

    public record Tolerance(int maxVariance, int foundationDepth, int maxSupportDepth) {
        public Tolerance {
            if (maxVariance < 0 || maxVariance > 4 || foundationDepth < 1 || foundationDepth > 3
                    || maxSupportDepth < 0 || maxSupportDepth > 4) throw new IllegalArgumentException("Unsafe plot tolerance");
        }
    }
}

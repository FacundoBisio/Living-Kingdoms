package dev.livingkingdoms.config;

import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.structure.BuildingKind;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.EnumMap;
import java.util.Map;

public final class ConstructionConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.IntValue TEST_DURATION;
    private static final Map<BuildingKind, ModConfigSpec.IntValue> DURATIONS = new EnumMap<>(BuildingKind.class);
    private static final Map<BuildingKind, Map<ResourceKind, ModConfigSpec.IntValue>> COSTS = new EnumMap<>(BuildingKind.class);
    private ConstructionConfig() {}
    public static void define(ModConfigSpec.Builder builder) {
        builder.push("construction");
        ENABLED = builder.comment("New wilderness Charter foundations start with a camp. Existing settlements and conversions are unaffected.")
                .define("progressiveFounding", true);
        TEST_DURATION = builder.comment("Development duration override in ticks; 0 uses gameplay durations. Existing projects retain their saved duration.")
                .defineInRange("testingDurationTicks", 0, 0, 1728000);
        defineBuilding(builder, BuildingKind.TOWN_HALL, 3600, 64, 32, 4);
        defineBuilding(builder, BuildingKind.HOUSE, 1800, 32, 16, 0);
        defineBuilding(builder, BuildingKind.FARM, 2400, 32, 16, 2);
        defineBuilding(builder, BuildingKind.BARRACKS, 3600, 48, 32, 8);
        builder.pop();
    }
    private static void defineBuilding(ModConfigSpec.Builder b, BuildingKind kind, int ticks, int logs, int stone, int iron) {
        b.push(kind.name().toLowerCase(java.util.Locale.ROOT));
        DURATIONS.put(kind, b.defineInRange("durationTicks", ticks, 20, 1728000));
        Map<ResourceKind,ModConfigSpec.IntValue> costs = new EnumMap<>(ResourceKind.class);
        costs.put(ResourceKind.LOGS, b.defineInRange("logs", logs, 1, 2304));
        costs.put(ResourceKind.STONE, b.comment("Minecraft stone blocks, matching Quest Board material icons.").defineInRange("stone", stone, 1, 2304));
        costs.put(ResourceKind.IRON_INGOT, b.defineInRange("ironIngots", iron, 0, 2304));
        COSTS.put(kind, costs); b.pop();
    }
    public static Map<ResourceKind,Integer> cost(BuildingKind kind) {
        Map<ResourceKind,Integer> result = new EnumMap<>(ResourceKind.class);
        COSTS.get(kind).forEach((resource, value) -> { if (value.get() > 0) result.put(resource, value.get()); });
        return Map.copyOf(result);
    }
    public static long duration(BuildingKind kind) { return TEST_DURATION.get() == 0 ? DURATIONS.get(kind).get() : Math.max(20, TEST_DURATION.get()); }
}

package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EconomyConfig {
    public static ModConfigSpec.IntValue BASE_CAPACITY,WAREHOUSE_CAPACITY,CONSUMPTION_INTERVAL,CITIZENS_PER_FOOD;
    public static ModConfigSpec.IntValue PRODUCTION_INTERVAL,WORKPLACE_SLOTS,TRADE_DISTANCE,DAILY_TRADES,MAX_BUILDING_LEVEL,MAX_TERRITORY_RADIUS;
    private EconomyConfig() {}
    public static void define(ModConfigSpec.Builder builder) {
        builder.push("kingdomEconomy");
        BASE_CAPACITY=builder.defineInRange("baseMaterialCapacity",256,64,100000);
        WAREHOUSE_CAPACITY=builder.defineInRange("capacityPerWarehouseLevel",256,64,100000);
        CONSUMPTION_INTERVAL=builder.defineInRange("foodConsumptionIntervalTicks",2400,200,24000);
        CITIZENS_PER_FOOD=builder.defineInRange("citizensPerFoodUnit",4,1,100);
        PRODUCTION_INTERVAL=builder.defineInRange("productionIntervalTicks",1200,200,24000);
        WORKPLACE_SLOTS=builder.defineInRange("economicWorkplaceSlots",2,1,8);
        TRADE_DISTANCE=builder.defineInRange("tradeMaxDistance",1536,128,4096);
        DAILY_TRADES=builder.defineInRange("tradeLimitPerKingdomPerDay",4,1,32);
        MAX_BUILDING_LEVEL=builder.defineInRange("maxBuildingLevel",3,1,5);
        MAX_TERRITORY_RADIUS=builder.defineInRange("maxExpandedTerritoryRadius",96,48,128);
        builder.pop();
    }
}

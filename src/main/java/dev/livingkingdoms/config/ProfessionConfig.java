package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ProfessionConfig {
    public static ModConfigSpec.IntValue FARM_SLOTS,FOOD_CAPACITY,WORK_COOLDOWN,SCAN_BUDGET,XP_PER_HARVEST,XP_STEP,LEVEL_CAP,WHEAT_FOOD,CARROT_FOOD,POTATO_FOOD,LOW_FOOD,HEALTHY_FOOD;
    public static ModConfigSpec.DoubleValue FOOD_PORTION,LOW_FOOD_MODIFIER;
    private ProfessionConfig() {}
    static void define(ModConfigSpec.Builder b) {
        b.push("professions"); b.push("farmer");
        FARM_SLOTS=b.defineInRange("basicFarmWorkerSlots",2,1,128);
        WORK_COOLDOWN=b.defineInRange("workCooldownTicks",120,40,24000);
        SCAN_BUDGET=b.comment("Maximum bounded crop cells inspected per loaded worker opportunity.").defineInRange("cropScanBudget",16,1,32);
        XP_PER_HARVEST=b.defineInRange("xpPerHarvest",5,1,1000); XP_STEP=b.defineInRange("xpStep",20,1,100000);
        LEVEL_CAP=b.defineInRange("levelCap",5,1,20);
        b.pop(); b.push("food");
        FOOD_CAPACITY=b.defineInRange("baseCapacity",500,1,1000000);
        WHEAT_FOOD=b.defineInRange("wheatUnits",2,1,100); CARROT_FOOD=b.defineInRange("carrotUnits",2,1,100); POTATO_FOOD=b.defineInRange("potatoUnits",2,1,100);
        FOOD_PORTION=b.comment("Portion of gross crop food converted to stock; remaining produce is discarded. No item drops or duplicate items.").defineInRange("contributionPortion",1.0,0.0,1.0);
        LOW_FOOD=b.defineInRange("lowStockThreshold",20,0,1000000); HEALTHY_FOOD=b.defineInRange("healthyStockThreshold",80,1,1000000);
        LOW_FOOD_MODIFIER=b.comment("Chance multiplier at low food; housing remains mandatory.").defineInRange("lowStockImmigrationMultiplier",0.1,0.0,1.0);
        b.pop(); b.pop();
    }
}

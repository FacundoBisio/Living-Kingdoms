package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class GuardConfig {
    public static ModConfigSpec.IntValue SLOTS,DEFENSE_RANGE,CHASE_RANGE,SEARCH_RANGE,SEARCH_INTERVAL,XP_STEP,LEVEL_CAP,XP_PER_UNIT,IRON_ARMOR_LEVEL,ENCHANT_LEVEL,ALERT_COOLDOWN;
    public static ModConfigSpec.DoubleValue BASE_DAMAGE,DAMAGE_PER_LEVEL,HEALTH_PER_LEVEL,ENCHANT_CHANCE,LOW_SECURITY_MODIFIER;
    private GuardConfig() {}
    static void define(ModConfigSpec.Builder b) {
        b.push("guards");
        SLOTS=b.defineInRange("basicBarracksSlots",4,1,128);
        DEFENSE_RANGE=b.defineInRange("defenseRadius",32,8,64);
        CHASE_RANGE=b.comment("Distance beyond the settlement defense boundary before pursuit stops.").defineInRange("chaseMargin",8,0,16);
        SEARCH_RANGE=b.defineInRange("localSearchRadius",16,4,24);
        SEARCH_INTERVAL=b.defineInRange("searchIntervalTicks",40,20,200);
        XP_STEP=b.defineInRange("xpStep",40,1,100000); LEVEL_CAP=b.defineInRange("levelCap",5,1,20);
        XP_PER_UNIT=b.comment("XP for each four actual hostile damage points; each victim has a persisted finite budget.").defineInRange("xpPerDamageUnit",2,1,100);
        BASE_DAMAGE=b.defineInRange("baseAttackDamage",4.0,1.0,8.0);
        DAMAGE_PER_LEVEL=b.defineInRange("damagePerLevel",0.25,0.0,0.5);
        HEALTH_PER_LEVEL=b.defineInRange("healthPerLevel",1.0,0.0,2.0);
        IRON_ARMOR_LEVEL=b.defineInRange("ironArmorLevel",3,1,20);
        ENCHANT_LEVEL=b.defineInRange("enchantLevel",4,1,20);
        ENCHANT_CHANCE=b.defineInRange("protectionOneChance",0.15,0.0,0.3);
        LOW_SECURITY_MODIFIER=b.defineInRange("lowSecurityImmigrationMultiplier",0.85,0.5,1.0);
        ALERT_COOLDOWN=b.defineInRange("alertCooldownTicks",1200,200,24000);
        b.pop();
    }
}

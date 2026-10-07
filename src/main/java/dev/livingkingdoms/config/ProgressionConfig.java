package dev.livingkingdoms.config;

import dev.livingkingdoms.progression.domain.EquipmentRules;
import dev.livingkingdoms.progression.domain.RegionalRules;
import dev.livingkingdoms.progression.domain.StatRules;
import net.neoforged.neoforge.common.ModConfigSpec;

/** One focused section of the existing per-world server config. Values snapshot on spawn. */
public final class ProgressionConfig {
    private static ModConfigSpec.IntValue maxLevel, baseLevel, blocksPerBand, levelsPerBand, distanceCap,
            daysPerLevel, ageCap, activityPerLevel, activityCap, tierWeight, tierCap, variance,
            lightLevel, standardLevel, advancedLevel, enchantLevel;
    private static ModConfigSpec.DoubleValue healthPerLevel, healthCap, damagePerLevel, damageCap,
            armorPerLevel, armorCap, speedPerLevel, speedCap, eliteStatBonus, eliteArmorBonus, enchantChance;
    public static ModConfigSpec.IntValue ORIGIN_X, ORIGIN_Z, ACTIVITY_RADIUS, ELITE_MINIMUM;
    public static ModConfigSpec.DoubleValue ELITE_CHANCE;
    public static ModConfigSpec.BooleanValue SHOW_LEVEL_NAMES;
    private ProgressionConfig() {}

    static void define(ModConfigSpec.Builder builder) {
        builder.push("progression");
        RegionalRules r = RegionalRules.defaults();
        maxLevel = builder.comment("Maximum level for new hostile members; existing snapshots retain their level.").defineInRange("maximumLevel", r.maximumLevel(), 1, 100);
        baseLevel = builder.defineInRange("baseLevel", r.baseLevel(), 1, 100);
        ORIGIN_X = builder.comment("Starting-region center for horizontal distance bands; no player equipment input.").defineInRange("originX", 0, -30_000_000, 30_000_000);
        ORIGIN_Z = builder.defineInRange("originZ", 0, -30_000_000, 30_000_000);
        blocksPerBand = builder.defineInRange("blocksPerDistanceBand", r.blocksPerBand(), 128, 100000);
        levelsPerBand = builder.defineInRange("levelsPerDistanceBand", r.levelsPerBand(), 0, 10);
        distanceCap = builder.defineInRange("maximumDistanceContribution", r.maximumDistanceBonus(), 0, 100);
        daysPerLevel = builder.comment("Uses elapsed server game ticks / 24000, unaffected by sleep or /time set.").defineInRange("worldDaysPerLevel", r.daysPerLevel(), 1, 100000);
        ageCap = builder.defineInRange("maximumWorldAgeContribution", r.maximumAgeBonus(), 0, 20);
        ACTIVITY_RADIUS = builder.comment("Loaded or unloaded active encounter origins from the metadata index; no entity/chunk access.").defineInRange("activityRadius", 192, 0, 1024);
        activityPerLevel = builder.defineInRange("activityThreatPerLevel", r.activityPerLevel(), 1, 1000);
        activityCap = builder.defineInRange("maximumActivityContribution", r.maximumActivityBonus(), 0, 20);
        tierWeight = builder.comment("Future caller-supplied hostile settlement tier input; current runtime supplies zero.").defineInRange("levelsPerHostileSettlementTier", r.levelsPerHostileTier(), 0, 10);
        tierCap = builder.defineInRange("maximumHostileTierContribution", r.maximumHostileTierBonus(), 0, 100);
        variance = builder.defineInRange("memberLevelVariance", r.memberVariance(), 0, 10);
        SHOW_LEVEL_NAMES = builder.comment("Opt-in level labels for new members; default preserves normal names and visibility.").define("showLevelNames", false);
        builder.push("stats");
        StatRules s = StatRules.defaults();
        healthPerLevel = builder.defineInRange("healthBonusPerLevel", s.healthPerLevel(), 0.0, 1.0);
        healthCap = builder.comment("Additive fraction of vanilla base; even maximum config allows at most +100% from this system.").defineInRange("maximumHealthBonus", s.healthCap(), 0.0, 1.0);
        damagePerLevel = builder.defineInRange("damageBonusPerLevel", s.damagePerLevel(), 0.0, 1.0);
        damageCap = builder.defineInRange("maximumDamageBonus", s.damageCap(), 0.0, 1.0);
        armorPerLevel = builder.defineInRange("armorPerLevel", s.armorPerLevel(), 0.0, 8.0);
        armorCap = builder.defineInRange("maximumArmorBonus", s.armorCap(), 0.0, 8.0);
        speedPerLevel = builder.defineInRange("movementBonusPerLevel", s.speedPerLevel(), 0.0, .15);
        speedCap = builder.defineInRange("maximumMovementBonus", s.speedCap(), 0.0, .15);
        eliteStatBonus = builder.defineInRange("eliteHealthDamageBonus", s.eliteStatBonus(), 0.0, .5);
        eliteArmorBonus = builder.defineInRange("eliteArmorBonus", s.eliteArmorBonus(), 0.0, 4.0);
        builder.pop().push("equipment");
        EquipmentRules e = EquipmentRules.defaults();
        lightLevel = builder.defineInRange("leatherLevel", e.lightLevel(), 1, 100);
        standardLevel = builder.defineInRange("chainmailLevel", e.standardLevel(), 1, 100);
        advancedLevel = builder.defineInRange("ironLevel", e.advancedLevel(), 1, 100);
        enchantLevel = builder.defineInRange("enchantmentMinimumLevel", e.enchantLevel(), 1, 100);
        enchantChance = builder.defineInRange("enchantmentChance", e.enchantChance(), 0.0, 1.0);
        builder.pop().push("elite");
        ELITE_MINIMUM = builder.defineInRange("minimumLevel", 20, 1, 100);
        ELITE_CHANCE = builder.defineInRange("chance", .05, 0.0, 1.0);
        builder.pop().pop();
    }

    public static RegionalRules regionalRules() {
        // Access through the shared spec guarantees initialization even in isolated tests/tools.
        var ignored = KingdomConfig.SPEC;
        return new RegionalRules(maxLevel.get(), baseLevel.get(), blocksPerBand.get(), levelsPerBand.get(), distanceCap.get(),
                daysPerLevel.get(), ageCap.get(), activityPerLevel.get(), activityCap.get(), tierWeight.get(), tierCap.get(), variance.get());
    }
    public static StatRules statRules() {
        var ignored = KingdomConfig.SPEC;
        return new StatRules(healthPerLevel.get(), healthCap.get(), damagePerLevel.get(), damageCap.get(), armorPerLevel.get(),
                armorCap.get(), speedPerLevel.get(), speedCap.get(), eliteStatBonus.get(), eliteArmorBonus.get());
    }
    public static EquipmentRules equipmentRules() {
        var ignored = KingdomConfig.SPEC;
        // Unordered edited thresholds are normalized conservatively, without breaking old worlds/config loading.
        int light = lightLevel.get(), standard = Math.max(light, standardLevel.get()), advanced = Math.max(standard, advancedLevel.get());
        return new EquipmentRules(light, standard, advanced, enchantLevel.get(), enchantChance.get());
    }
}

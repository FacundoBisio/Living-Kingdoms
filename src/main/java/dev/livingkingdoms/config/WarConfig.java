package dev.livingkingdoms.config;

import dev.livingkingdoms.war.domain.Bounty;
import net.neoforged.neoforge.common.ModConfigSpec;

/** [war] hostile NPC kingdoms, warbands and garrisons; [rewards] party bounties and player renown. */
public final class WarConfig {
    public static ModConfigSpec.BooleanValue ENABLED;
    public static ModConfigSpec.DoubleValue HOSTILE_CHANCE;
    public static ModConfigSpec.IntValue CHECK_INTERVAL;
    public static ModConfigSpec.IntValue RAID_RANGE;
    public static ModConfigSpec.IntValue RAID_COOLDOWN;
    public static ModConfigSpec.IntValue FIRST_RAID_DELAY;
    public static ModConfigSpec.IntValue WARBAND_SIZE;
    public static ModConfigSpec.IntValue WARBAND_THREAT;
    public static ModConfigSpec.IntValue WARBAND_REPUTATION;
    public static ModConfigSpec.BooleanValue GARRISON_ENABLED;
    public static ModConfigSpec.IntValue GARRISON_COOLDOWN;
    public static ModConfigSpec.BooleanValue WAR_STANDARDS;
    public static ModConfigSpec.BooleanValue BOUNTY_ENABLED;
    public static ModConfigSpec.DoubleValue BOUNTY_EMERALD_BASE;
    public static ModConfigSpec.DoubleValue BOUNTY_EMERALD_PER_LEVEL;
    public static ModConfigSpec.IntValue BOUNTY_EMERALD_CAP;
    public static ModConfigSpec.DoubleValue BOUNTY_XP_PER_LEVEL;
    public static ModConfigSpec.IntValue BOUNTY_XP_CAP;
    public static ModConfigSpec.BooleanValue RENOWN_ENABLED;

    private WarConfig() {}

    static void define(ModConfigSpec.Builder builder) {
        builder.comment("Hostile NPC kingdoms. A share of NPC kingdoms is at war with player kingdoms: it sends warbands",
                "that march on nearby player kingdoms through the normal threat flow, and keeps a garrison at home.",
                "One bounded check per interval, for one online player; chunks are never loaded.").push("war");
        ENABLED = builder.comment("Enable hostile NPC kingdoms, warbands and garrisons.")
                .define("enabled", true);
        HOSTILE_CHANCE = builder.comment("Seeded share of NPC kingdoms that are hostile. Decided once per kingdom and saved.")
                .defineInRange("hostileChance", 0.4, 0.0, 1.0);
        CHECK_INTERVAL = builder.comment("Server ticks between war checks; each check handles one online player.")
                .defineInRange("checkIntervalTicks", 600, 100, 24000);
        RAID_RANGE = builder.comment("Maximum distance from a hostile kingdom to a player kingdom it raids, in blocks.")
                .defineInRange("raidRange", 1536, 256, 8192);
        RAID_COOLDOWN = builder.comment("Ticks between raids of one hostile kingdom at strength 1; down to half at strength 10.")
                .defineInRange("raidCooldownTicks", 24000, 1200, 1728000);
        FIRST_RAID_DELAY = builder.comment("Ticks a newly discovered hostile kingdom waits before its first raid.")
                .defineInRange("firstRaidDelayTicks", 12000, 0, 1728000);
        WARBAND_SIZE = builder.comment("Warband members at strength 1; one more every two strength levels (max 8).")
                .defineInRange("warbandSize", 4, 2, 8);
        WARBAND_THREAT = builder.comment("Threat rating of warbands; metadata used by difficulty and alerts.")
                .defineInRange("warbandThreat", 3, 1, 100);
        WARBAND_REPUTATION = builder.comment("Reputation awarded with the defended kingdom for defeating a warband.")
                .defineInRange("warbandReputation", 6, 0, 1000000);
        GARRISON_ENABLED = builder.comment("Hostile kingdoms field a garrison when a player enters their territory.")
                .define("garrisonEnabled", true);
        GARRISON_COOLDOWN = builder.comment("Ticks before a hostile kingdom fields a new garrison after the last one is gone.")
                .defineInRange("garrisonCooldownTicks", 6000, 600, 1728000);
        WAR_STANDARDS = builder.comment("Place black war standards at the edges of hostile kingdoms the first time they are visited.")
                .define("warStandards", true);
        builder.pop();
        builder.comment("Loot and player ranks for defeating real, reward-eligible hostile parties.").push("rewards");
        BOUNTY_ENABLED = builder.comment("Defeated parties drop a bounty (emeralds, iron for warbands) and experience.")
                .define("bountyEnabled", true);
        BOUNTY_EMERALD_BASE = builder.comment("Emeralds per member before level scaling.")
                .defineInRange("emeraldBase", 0.5, 0.0, 8.0);
        BOUNTY_EMERALD_PER_LEVEL = builder.comment("Extra emeralds per member and level.")
                .defineInRange("emeraldPerLevel", 0.15, 0.0, 4.0);
        BOUNTY_EMERALD_CAP = builder.comment("Maximum emeralds in one bounty.")
                .defineInRange("emeraldCap", 24, 0, Bounty.MAX_EMERALDS);
        BOUNTY_XP_PER_LEVEL = builder.comment("Experience per member and level.")
                .defineInRange("experiencePerLevel", 3.0, 0.0, 50.0);
        BOUNTY_XP_CAP = builder.comment("Maximum experience in one bounty.")
                .defineInRange("experienceCap", 400, 0, Bounty.MAX_EXPERIENCE);
        RENOWN_ENABLED = builder.comment("Players earn renown and one-time rank rewards for defeating hostile parties.")
                .define("renownEnabled", true);
        builder.pop();
    }

    public static Bounty.Rules bountyRules() {
        return new Bounty.Rules(BOUNTY_EMERALD_BASE.get(), BOUNTY_EMERALD_PER_LEVEL.get(), BOUNTY_EMERALD_CAP.get(),
                BOUNTY_XP_PER_LEVEL.get(), BOUNTY_XP_CAP.get());
    }
}

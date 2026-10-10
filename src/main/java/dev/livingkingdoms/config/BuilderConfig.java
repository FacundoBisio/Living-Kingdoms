package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BuilderConfig {
    public static ModConfigSpec.IntValue SLOTS,LEVEL_CAP,XP_STEP,XP_PER_STAGE,XP_COMPLETION,WORK_INTERVAL,WORK_RANGE;
    public static ModConfigSpec.DoubleValue MAX_SPEED_BONUS;
    private BuilderConfig() {}
    static void define(ModConfigSpec.Builder b) {
        b.push("builders");
        SLOTS=b.comment("Slots per Town Hall/Core; a converted village's existing plaza provides the same administrative slots.")
                .defineInRange("townHallSlots",2,1,128);
        LEVEL_CAP=b.defineInRange("levelCap",5,1,20);
        XP_STEP=b.defineInRange("xpStep",40,1,100000);
        XP_PER_STAGE=b.defineInRange("xpPerConstructionStage",5,1,1000);
        XP_COMPLETION=b.defineInRange("xpPerCompletedProject",20,1,1000);
        MAX_SPEED_BONUS=b.comment("Maximum additional work rate at the level cap.").defineInRange("maximumSpeedBonus",.25,0,.25);
        WORK_INTERVAL=b.defineInRange("workIntervalTicks",20,20,200);
        WORK_RANGE=b.defineInRange("workDistanceBlocks",4,2,6);
        b.pop();
    }
}

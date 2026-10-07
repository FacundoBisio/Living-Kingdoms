package dev.livingkingdoms.config;

import dev.livingkingdoms.quest.expansion.domain.QuestRules;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Reuses the per-world server configuration; the original quests section and accepted Iron terms stay valid. */
public final class QuestExpansionConfig {
    private static ModConfigSpec.IntValue count, refresh, expiration, range, iron, wheat, logs, stone,
            resourceEmeralds, resourceReputation, combatEmeralds, combatReputation, mainReturnEmeralds,
            mainReturnReputation, emeraldStep, reputationStep, emeraldCap, reputationCap;
    private QuestExpansionConfig() {}

    static void define(ModConfigSpec.Builder builder) {
        QuestRules rules = QuestRules.defaults();
        builder.push("quests").push("expansion");
        count = builder.comment("Maximum dynamic offers per board batch; three resource fallbacks exist when no combat target is available.")
                .defineInRange("dynamicCount", rules.dynamicCount(), 2, 4);
        refresh = builder.comment("Persistent refresh cooldown in elapsed server ticks; opening a board cannot reroll requests.")
                .defineInRange("refreshTicks", rules.refreshTicks(), 200, 1_728_000);
        expiration = builder.comment("Lifetime of newly generated dynamic requests; main quests never expire.")
                .defineInRange("expirationTicks", rules.expirationTicks(), 200, 1_728_000);
        range = builder.comment("Maximum indexed hostile-party origin distance from the settlement center. No chunk loading.")
                .defineInRange("encounterRange", rules.encounterRange(), 0, 4096);
        iron = builder.comment("Dynamic Iron request quantity; the main Iron quest keeps the existing quests.requiredIron setting.")
                .defineInRange("requiredIron", rules.requiredIron(), 1, 2304);
        wheat = builder.defineInRange("requiredWheat", rules.requiredWheat(), 1, 2304);
        logs = builder.defineInRange("requiredLogs", rules.requiredLogs(), 1, 2304);
        stone = builder.defineInRange("requiredStone", rules.requiredStone(), 1, 2304);
        builder.push("rewards");
        resourceEmeralds = builder.defineInRange("resourceEmeralds", rules.resourceBaseEmeralds(), 0, 2304);
        resourceReputation = builder.defineInRange("resourceReputation", rules.resourceBaseReputation(), 0, 1000000);
        combatEmeralds = builder.defineInRange("combatEmeralds", rules.combatBaseEmeralds(), 0, 2304);
        combatReputation = builder.defineInRange("combatReputation", rules.combatBaseReputation(), 0, 1000000);
        mainReturnEmeralds = builder.defineInRange("mainReturnEmeralds", rules.mainReturnEmeralds(), 0, 2304);
        mainReturnReputation = builder.defineInRange("mainReturnReputation", rules.mainReturnReputation(), 0, 1000000);
        emeraldStep = builder.defineInRange("emeraldsPerDifficulty", rules.emeraldsPerDifficulty(), 0, 2304);
        reputationStep = builder.defineInRange("reputationPerDifficulty", rules.reputationPerDifficulty(), 0, 1000000);
        emeraldCap = builder.defineInRange("maximumEmeralds", rules.maximumEmeralds(), 0, 2304);
        reputationCap = builder.defineInRange("maximumReputation", rules.maximumReputation(), 0, 1000000);
        builder.pop().pop().pop();
    }
    public static QuestRules rules() {
        var ignored = KingdomConfig.SPEC;
        return new QuestRules(count.get(), refresh.get(), expiration.get(), range.get(), iron.get(), wheat.get(),
                logs.get(), stone.get(), resourceEmeralds.get(), resourceReputation.get(), combatEmeralds.get(),
                combatReputation.get(), mainReturnEmeralds.get(), mainReturnReputation.get(), emeraldStep.get(),
                reputationStep.get(), emeraldCap.get(), reputationCap.get());
    }
}

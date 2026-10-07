package dev.livingkingdoms.progression;

import dev.livingkingdoms.config.ProgressionConfig;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.progression.domain.RegionalDifficulty;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** On-demand metadata-only progression; neither player equipment nor chunk loading participates. */
public final class RegionalDifficultyService {
    private RegionalDifficultyService() {}
    public static RegionalDifficulty at(ServerLevel level, BlockPos position) {
        var rules = ProgressionConfig.regionalRules();
        var data = EncounterSavedData.get(level.getServer()); // Includes the common server-thread guard.
        double distance = Math.hypot((double) position.getX() - ProgressionConfig.ORIGIN_X.get(),
                (double) position.getZ() - ProgressionConfig.ORIGIN_Z.get());
        long days = Math.max(0, level.getServer().overworld().getGameTime()) / 24000;
        long activity = data.activeNearby(level.dimension().location().toString(), position.getX(), position.getZ(),
                ProgressionConfig.ACTIVITY_RADIUS.get()).stream().filter(party -> !party.debug())
                .mapToLong(party -> party.threatRating()).sum();
        return RegionalDifficulty.calculate(distance, days, (int) Math.min(Integer.MAX_VALUE, activity), 0, rules);
    }
}

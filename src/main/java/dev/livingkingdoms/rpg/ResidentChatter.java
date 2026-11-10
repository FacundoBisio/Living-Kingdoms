package dev.livingkingdoms.rpg;

import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.defense.domain.SettlementSafety;
import dev.livingkingdoms.profession.domain.ProfessionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import java.util.Locale;
import java.util.UUID;

/**
 * Small talk for residents: a random line chosen from the situation (danger, night, rain, festival), the
 * resident's job or a pool of odd everyday thoughts. Returned as a translation key plus arguments
 * ({@code %1$s} player name, {@code %2$s} kingdom name) so every line exists in each supported language.
 */
public final class ResidentChatter {
    public static final int PROFESSION_LINES = 5, ODD_LINES = 12, SITUATION_LINES = 3;
    private ResidentChatter() {}

    public static CompoundTag line(ServerLevel level, UUID settlement, ProfessionType profession, String player, String kingdom, RandomSource random) {
        String key;
        long time = Math.floorMod(level.getDayTime(), 24000);
        if (DefenseService.status(level.getServer(), settlement) == SettlementSafety.THREATENED || AlarmService.active(level.getServer(), settlement))
            key = "danger." + random.nextInt(SITUATION_LINES);
        else if (EventService.festival(level.getServer(), settlement) && random.nextInt(2) == 0) key = "festival." + random.nextInt(SITUATION_LINES);
        else if (level.isRaining() && random.nextInt(3) == 0) key = "rain." + random.nextInt(SITUATION_LINES);
        else if (time >= 13000 && time < 23000 && random.nextInt(3) == 0) key = "night." + random.nextInt(SITUATION_LINES);
        else if (random.nextInt(2) == 0) key = job(profession) + "." + random.nextInt(PROFESSION_LINES);
        else key = "odd." + random.nextInt(ODD_LINES);
        var tag = new CompoundTag();
        tag.putString("key", "resident.livingkingdoms.chatter." + key);
        tag.putString("player", player);
        tag.putString("kingdom", kingdom);
        return tag;
    }

    /** Profession pool id; jobs without their own lines share the idle pool. */
    public static String job(ProfessionType profession) {
        return switch (profession) {
            case FARMER, GUARD, BUILDER, LUMBERJACK, MINER, BLACKSMITH, MERCHANT -> profession.name().toLowerCase(Locale.ROOT);
            default -> "idle";
        };
    }
}

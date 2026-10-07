package dev.livingkingdoms.progression;

import dev.livingkingdoms.config.ProgressionConfig;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.EquipmentRules;
import dev.livingkingdoms.progression.domain.LevelSummary;
import dev.livingkingdoms.progression.domain.PartyLevels;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.List;

/** One shared regional roll for every hostile faction; stat/equipment policies remain independent of AI. */
public final class ProgressionService {
    private ProgressionService() {}
    public static LevelSummary initializeParty(ServerLevel level, BlockPos origin, Faction faction, List<Mob> members) {
        if (faction.isAllied()) throw new IllegalArgumentException("Civilian/allied roles need their own progression policy");
        for (Mob mob : members) {
            if (mob.level() != level || dev.livingkingdoms.encounter.EncounterMember.read(mob)
                    .filter(identity -> identity.faction() == faction).isEmpty()) throw new IllegalArgumentException("Invalid managed progression member");
        }
        var rules = ProgressionConfig.regionalRules();
        var region = RegionalDifficultyService.at(level, origin);
        var stats = ProgressionConfig.statRules();
        var levels = PartyLevels.generate(region, rules, members.size(), level.random::nextInt);
        for (int i = 0; i < members.size(); i++) {
            Mob mob = members.get(i);
            var memberLevel = levels.get(i);
            boolean elite = isElite(memberLevel.value(), ProgressionConfig.ELITE_MINIMUM.get(), ProgressionConfig.ELITE_CHANCE.get(), level.random.nextDouble());
            EntityProgression.initializeSpawn(mob, new EntityProgression.Profile(memberLevel, elite, stats.scaling(memberLevel, elite)));
            EquipmentProgression.apply(mob, faction, memberLevel);
            if (ProgressionConfig.SHOW_LEVEL_NAMES.get()) {
                Component name = Component.translatable("progression.livingkingdoms.level_name", mob.getName(), memberLevel.value());
                if (elite) name = Component.translatable("progression.livingkingdoms.elite_name", name).withStyle(ChatFormatting.GOLD);
                mob.setCustomName(name); mob.setCustomNameVisible(true);
            }
        }
        return LevelSummary.of(levels);
    }
    public static boolean isElite(int level, int minimum, double chance, double roll) {
        EquipmentRules.validRoll(roll);
        if (level < 1 || level > 100 || minimum < 1 || minimum > 100 || !Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("Invalid elite rules");
        return level >= minimum && roll < chance;
    }
}

package dev.livingkingdoms.rpg;

import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/**
 * Ringing a bell inside your kingdom sounds the alarm for two minutes: civilians run for shelter, guards stay
 * on watch at night and every monster in the territory glows. Purely transient; a restart silences it.
 */
public final class AlarmService {
    public static final long DURATION = 2400, PULSE = 100;
    private static final Map<UUID, Long> ALARMS = new HashMap<>();
    private AlarmService() {}

    public static boolean active(MinecraftServer server, UUID settlement) {
        var until = ALARMS.get(settlement);
        return until != null && server.overworld().getGameTime() < until;
    }
    public static long remaining(MinecraftServer server, UUID settlement) {
        var until = ALARMS.get(settlement);
        return until == null ? 0 : Math.max(0, until - server.overworld().getGameTime());
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.getBlockState(event.getPos()).is(Blocks.BELL)) return;
        var settlement = SettlementSavedData.get(player.server).alliedAt(level.dimension().location().toString(), event.getPos().getX(), event.getPos().getZ()).orElse(null);
        if (settlement == null || !settlement.faction().isAllied() || !KingdomOnboardingService.canHelp(player, settlement)) return;
        sound(level, settlement);
    }

    /** Starts (or extends) the alarm. Returns true when it was not already ringing. */
    public static boolean sound(ServerLevel level, Settlement settlement) {
        boolean fresh = !active(level.getServer(), settlement.id());
        ALARMS.put(settlement.id(), level.getServer().overworld().getGameTime() + DURATION);
        if (fresh) {
            var t = settlement.territory();
            for (var player : level.players()) if (t.contains(t.dimension(), player.getBlockX(), player.getBlockZ()))
                player.sendSystemMessage(Component.translatable("alarm.livingkingdoms.sounded", VillageNames.display(settlement)).withStyle(ChatFormatting.RED));
            pulse(level, settlement);
        }
        return fresh;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (ALARMS.isEmpty()) return;
        var server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % PULSE != 0) return;
        for (var it = ALARMS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var settlement = SettlementSavedData.get(server).get(entry.getKey()).orElse(null);
            if (settlement == null || now >= entry.getValue()) {
                it.remove();
                if (settlement != null) for (var player : server.getPlayerList().getPlayers())
                    if (settlement.territory().contains(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ()))
                        player.sendSystemMessage(Component.translatable("alarm.livingkingdoms.ended").withStyle(ChatFormatting.GRAY));
                continue;
            }
            for (var level : server.getAllLevels())
                if (level.dimension().location().toString().equals(settlement.territory().dimension())) pulse(level, settlement);
        }
    }

    private static void pulse(ServerLevel level, Settlement settlement) {
        var t = settlement.territory();
        int r = Math.min(t.radius(), 96);
        var box = new AABB(t.x() - r, t.y() - 32, t.z() - r, t.x() + r + 1, t.y() + 48, t.z() + r + 1);
        int n = 0;
        for (var mob : level.getEntitiesOfClass(Mob.class, box, ArcherWork::shootable)) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, (int) PULSE + 40, 0, false, false));
            if (++n >= 64) break;
        }
    }

    public static void clear() { ALARMS.clear(); }
}

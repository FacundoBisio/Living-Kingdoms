package dev.livingkingdoms.profession;

import dev.livingkingdoms.config.GuardConfig;
import dev.livingkingdoms.profession.domain.GuardPolicy;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Derived from indexed settlement metadata, queried on demand. No ticking resource simulation. */
public final class SecurityService {
    private static final Map<MinecraftServer,Map<UUID,Long>> ALERTS=new WeakHashMap<>();
    private SecurityService() {}
    public static int score(MinecraftServer server,UUID settlement) {
        var d=ProfessionSavedData.get(server); return GuardPolicy.security(d.professions(settlement),d.buildings(settlement));
    }
    public static boolean lowSecurity(MinecraftServer server,UUID settlement) { return score(server,settlement)<32; }
    public static double immigrationModifier(MinecraftServer server,UUID settlement) {
        return GuardPolicy.immigrationModifier(score(server,settlement),GuardConfig.LOW_SECURITY_MODIFIER.get());
    }
    public static Map<ResourceKind,Integer> questWeights(MinecraftServer server,UUID settlement) {
        var weights=new EnumMap<ResourceKind,Integer>(ResourceKind.class); weights.putAll(ProfessionService.shortageWeights(server,settlement));
        if(!ProfessionSavedData.get(server).buildings(settlement).isEmpty() && lowSecurity(server,settlement)) weights.put(ResourceKind.IRON_INGOT,6);
        return Map.copyOf(weights);
    }
    /** Loaded members check on their own bounded clock; a split party can still approach a settlement. */
    public static void onEncounterTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if(!(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)
                || mob.tickCount%dev.livingkingdoms.config.DefenseConfig.DETECTION_INTERVAL.get()!=0
                || !(mob.level() instanceof ServerLevel level) || !mob.isAlive()) return;
        dev.livingkingdoms.defense.DefenseService.detectMember(level,mob);
    }
    public static void alert(ServerLevel level,Settlement settlement) {
        alert(level,settlement,false);
    }
    /** New events already have a persisted de-duplication receipt; each deserves one local warning. */
    public static void alertThreat(ServerLevel level,Settlement settlement) {
        alert(level,settlement,true);
    }
    private static void alert(ServerLevel level,Settlement settlement,boolean newEvent) {
        long now=level.getServer().overworld().getGameTime(); var cooldowns=ALERTS.computeIfAbsent(level.getServer(),s -> new HashMap<>());
        if(!newEvent && now<cooldowns.getOrDefault(settlement.id(),0L)) return;
        // Only occupied local regions need a feedback receipt; no offline settlement map growth.
        var players=level.players().stream().filter(p -> p.isAlive() && !p.isSpectator() && settlement.territory().contains(
                level.dimension().location().toString(),p.blockPosition().getX(),p.blockPosition().getZ())).toList();
        if(players.isEmpty()) return;
        cooldowns.entrySet().removeIf(e -> e.getValue()<=now);
        cooldowns.put(settlement.id(),now+GuardConfig.ALERT_COOLDOWN.get());
        for(var player:players) player.displayClientMessage(Component.translatable("defense.livingkingdoms.alert",settlement.name()),false);
    }
}

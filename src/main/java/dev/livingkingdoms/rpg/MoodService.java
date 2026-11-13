package dev.livingkingdoms.rpg;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.economy.TavernService;
import dev.livingkingdoms.npckingdom.NpcKingdoms;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.rpg.domain.Happiness;
import dev.livingkingdoms.rpg.domain.KingdomRank;
import dev.livingkingdoms.rpg.domain.Trait;
import dev.livingkingdoms.rpg.persistence.RpgSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.MinecraftServer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kingdom happiness and resident traits. Everything is derived from existing saved state; the only memory is a
 * small transient cache (one entry per kingdom, refreshed every {@link #TTL} ticks) so workers do not recompute
 * the kingdom's mood on every work tick.
 */
public final class MoodService {
    private static final int TTL = 200, MAX_CACHED = 64;
    private record Cached(Happiness happiness, long expires) {}
    private static final Map<UUID, Cached> CACHE = new LinkedHashMap<>();
    private MoodService() {}

    public static Happiness happiness(MinecraftServer server, UUID settlementId) {
        long now = server.overworld().getGameTime();
        var cached = CACHE.get(settlementId);
        if (cached != null && now < cached.expires() && now >= cached.expires() - TTL) return cached.happiness();
        var settlement = SettlementSavedData.get(server).get(settlementId).orElse(null);
        // A founding camp has no food economy yet: mood starts once the kingdom is established.
        var happiness = settlement == null || !settlement.faction().isAllied() || NpcKingdoms.isNpc(server, settlementId)
                || settlement.lifecycle() != dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED ? Happiness.NEUTRAL
                : Happiness.of(CitizenService.population(server, settlement), CitizenSavedData.get(server).summary(settlementId).total(),
                        ProfessionService.food(server, settlementId).stock() > 0, TavernService.levels(server, settlementId),
                        KingdomRank.level(RpgSavedData.get(server).kingdomXp(settlementId)));
        if (cached == null && CACHE.size() >= MAX_CACHED) CACHE.remove(CACHE.keySet().iterator().next());
        CACHE.put(settlementId, new Cached(happiness, now + TTL));
        return happiness;
    }

    /** Work speed factor of one resident: kingdom mood times their trait, bounded. */
    public static double workMultiplier(MinecraftServer server, UUID settlementId, UUID citizenId) {
        return Trait.work(happiness(server, settlementId).band(), Trait.of(citizenId));
    }

    /** Forgets the cached moods (server stop, and tests that change a kingdom and read it in the same tick). */
    public static void clear() { CACHE.clear(); }
}

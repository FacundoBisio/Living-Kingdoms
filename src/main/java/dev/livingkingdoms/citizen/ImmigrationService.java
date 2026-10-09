package dev.livingkingdoms.citizen;

import dev.livingkingdoms.advancement.KingdomMilestone;
import dev.livingkingdoms.citizen.domain.*;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.CitizenConfig;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Shared proposals and acceptance, serialized by Minecraft's server thread. Never creates chunk tickets. */
public final class ImmigrationService {
    private static final Map<MinecraftServer,Integer> CURSORS = new WeakHashMap<>();
    private ImmigrationService() {}

    public static long now(MinecraftServer server) { return Math.max(0,server.overworld().getGameTime()); }

    public static void onServerTick(ServerTickEvent.Post event) {
        var server=event.getServer();
        if(now(server)%CitizenConfig.CHECK_INTERVAL.get()!=0) return;
        var players=server.getPlayerList().getPlayers().stream().filter(p -> p.isAlive() && !p.isSpectator()).toList();
        if(players.isEmpty()) return;
        int cursor=Math.floorMod(CURSORS.getOrDefault(server,0),players.size());
        CURSORS.put(server,(cursor+1)%players.size());
        var player=players.get(cursor); var pos=player.blockPosition();
        // One metadata lookup for one active player per interval. No entity/settlement tick simulation.
        SettlementSavedData.get(server).at(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ())
                .filter(s -> s.faction().isAllied()).ifPresent(s -> attempt(player.serverLevel(),s,false));
    }

    public static List<ImmigrationCandidate> candidates(ServerLevel level, Settlement settlement) {
        CitizenService.ensureInitialized(level,settlement);
        var data=CitizenSavedData.get(level.getServer());
        data.expireCandidates(settlement.id(),now(level.getServer()));
        return data.candidates(settlement.id());
    }

    /** Debug bypasses chance and waiting only; housing, lifecycle and shared pending limits still apply. */
    public static Optional<ImmigrationCandidate> attempt(ServerLevel level, Settlement settlement, boolean debug) {
        return attempt(level,settlement,debug,(chance,factors) -> chance*
                dev.livingkingdoms.profession.ProfessionService.immigrationModifier(level.getServer(),settlement.id())
                * dev.livingkingdoms.profession.SecurityService.immigrationModifier(level.getServer(),settlement.id()));
    }

    public static Optional<ImmigrationCandidate> attempt(ServerLevel level, Settlement settlement, boolean debug,
            ImmigrationPolicy.Modifier modifier) {
        var server=level.getServer();
        if(!server.isSameThread()) throw new IllegalStateException("Immigration requires server thread");
        if(!validSettlement(level,settlement)) return Optional.empty();
        var data=CitizenSavedData.get(server);
        if(!data.initialized(settlement.id()) || debug) CitizenService.ensureInitialized(level,settlement);
        long now=now(server); data.expireCandidates(settlement.id(),now);
        if(!debug && data.nextCheck(settlement.id())==0) {
            data.schedule(settlement.id(),nextCheck(level,now)); return Optional.empty();
        }
        if(!debug && now<data.nextCheck(settlement.id())) return Optional.empty();
        data.schedule(settlement.id(),nextCheck(level,now));
        if(!eligible(settlement,data)) return Optional.empty();
        int reputation=level.players().stream().filter(p -> inside(p,settlement)).mapToInt(p ->
                QuestSavedData.get(server).reputation(p.getUUID(),settlement.id())).max().orElse(0);
        var factors=ImmigrationPolicy.Factors.neutral(reputation,settlement.level());
        if(!debug && level.random.nextDouble()>=ImmigrationPolicy.chance(CitizenConfig.IMMIGRATION_CHANCE.get(),factors,modifier))
            return Optional.empty();
        int low=Math.min(CitizenConfig.MIN_LEVEL.get(),CitizenConfig.MAX_LEVEL.get());
        int high=Math.max(CitizenConfig.MIN_LEVEL.get(),CitizenConfig.MAX_LEVEL.get());
        var candidate=new ImmigrationCandidate(UUID.randomUUID(),settlement.id(),
                CitizenNames.generate(new Random(level.random.nextLong())),new LevelValue(low+level.random.nextInt(high-low+1)),
                CitizenRole.UNASSIGNED,now,now+CitizenConfig.CANDIDATE_LIFETIME.get());
        if(!data.addCandidate(candidate)) return Optional.empty();
        for(var player:level.players()) if(inside(player,settlement)) player.displayClientMessage(
                Component.translatable("citizen.livingkingdoms.traveler",VillageNames.display(settlement)),false);
        return Optional.of(candidate);
    }

    private static boolean eligible(Settlement settlement,CitizenSavedData data) {
        return ImmigrationPolicy.eligible(CitizenConfig.IMMIGRATION_ENABLED.get(),settlement.lifecycle(),
                data.summary(settlement.id()).free(),data.candidates(settlement.id()).size(),
                CitizenConfig.MIN_FREE_HOUSING.get(),CitizenConfig.MAX_PENDING.get());
    }

    public static boolean accept(ServerPlayer player,UUID settlementId,UUID candidateId) {
        var server=player.server;
        if(!server.isSameThread()) throw new IllegalStateException("Acceptance requires server thread");
        var settlement=SettlementSavedData.get(server).get(settlementId).orElse(null);
        if(!authorized(player,settlement) || !CitizenConfig.IMMIGRATION_ENABLED.get()) return false;
        candidates(player.serverLevel(),settlement);
        var data=CitizenSavedData.get(server); var candidate=data.candidate(candidateId).orElse(null);
        if(candidate==null || !candidate.settlementId().equals(settlementId)
                || !ImmigrationPolicy.eligible(true,settlement.lifecycle(),data.summary(settlementId).free(),0,
                    CitizenConfig.MIN_FREE_HOUSING.get(),1)) return false;
        for(var home:data.houses(settlementId)) {
            if(home.status()!=HousingStatus.ACTIVE || data.citizens(settlementId).stream().filter(c ->
                    c.state()==CitizenState.ACTIVE && home.id().equals(c.homeId())).count()>=home.capacity()) continue;
            var prepared=CitizenService.prepareImmigrant(player.serverLevel(),settlement,home);
            if(prepared.isEmpty()) continue;
            var villager=prepared.orElseThrow();
            var accepted=data.acceptCandidate(settlementId,candidateId,villager.getUUID(),home.id(),now(server));
            if(accepted.isEmpty()) return false;
            boolean spawned=false;
            try {
                CitizenService.apply(accepted.orElseThrow(),villager);
                spawned=player.serverLevel().addFreshEntity(villager) && !villager.isRemoved();
            } catch(RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("Citizen arrival failed for {}",settlementId,failure);
            }
            if(!spawned) {
                villager.discard(); data.rollbackAcceptance(accepted.orElseThrow(),candidate); return false;
            }
            data.schedule(settlementId,nextCheck(player.serverLevel(),now(server)));
            KingdomMilestone.awardFirstCitizen(player);
            player.displayClientMessage(Component.translatable("citizen.livingkingdoms.accepted",candidate.name(),VillageNames.display(settlement)),false);
            return true;
        }
        return false;
    }

    public static boolean decline(ServerPlayer player,UUID settlementId,UUID candidateId) {
        var settlement=SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        if(!authorized(player,settlement)) return false;
        candidates(player.serverLevel(),settlement);
        var data=CitizenSavedData.get(player.server);
        if(data.candidate(candidateId).filter(c -> c.settlementId().equals(settlementId)).isEmpty()) return false;
        data.removeCandidate(candidateId); data.schedule(settlementId,nextCheck(player.serverLevel(),now(player.server)));
        player.displayClientMessage(Component.translatable("citizen.livingkingdoms.declined"),true); return true;
    }
    private static long nextCheck(ServerLevel level,long now) {
        int low=Math.min(CitizenConfig.MIN_COOLDOWN.get(),CitizenConfig.MAX_COOLDOWN.get());
        int high=Math.max(CitizenConfig.MIN_COOLDOWN.get(),CitizenConfig.MAX_COOLDOWN.get());
        return now+low+level.random.nextInt(high-low+1);
    }
    private static boolean validSettlement(ServerLevel level,Settlement settlement) {
        return settlement.faction().isAllied() && settlement.territory().dimension().equals(level.dimension().location().toString())
                && SettlementSavedData.get(level.getServer()).get(settlement.id()).filter(settlement::equals).isPresent();
    }
    private static boolean authorized(ServerPlayer player,Settlement settlement) {
        return settlement!=null && player.isAlive() && !player.isSpectator() && player.mayBuild()
                && validSettlement(player.serverLevel(),settlement) && inside(player,settlement);
    }
    private static boolean inside(ServerPlayer player,Settlement settlement) {
        return settlement.territory().contains(player.serverLevel().dimension().location().toString(),player.blockPosition().getX(),player.blockPosition().getZ());
    }
}

package dev.livingkingdoms.war;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.config.WarConfig;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.war.domain.KingdomStance;
import dev.livingkingdoms.war.domain.WarFront;
import dev.livingkingdoms.war.domain.WarPolicy;
import dev.livingkingdoms.war.persistence.WarSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Hostile NPC kingdoms at war with player kingdoms. Every {@code checkIntervalTicks} the server looks at one
 * online Overworld player (round robin): inside a hostile kingdom it may field that kingdom's garrison; inside
 * a player kingdom it may launch a raid from the nearest hostile kingdom in range. Runtime cost per check: one
 * indexed settlement lookup, one pass over the NPC kingdom registry (capped by {@code maxNpcKingdoms}) and one
 * pass over in-flight campaigns (at most 256). Spawning reuses the bounded encounter spawner and never loads chunks.
 */
public final class WarService {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Server-thread only, transient; a restart simply starts the rotation again.
    private static final Map<MinecraftServer, Integer> CURSORS = new WeakHashMap<>();

    private WarService() {}

    public static boolean enabled() {
        return KingdomConfig.SPEC.isLoaded() && WarConfig.ENABLED.get();
    }

    /** Read-only stance check for hot paths (combat resolution, rewards): never creates state. */
    public static boolean isHostile(MinecraftServer server, UUID settlement) {
        return settlement != null && enabled() && WarSavedData.get(server).hostile(settlement);
    }

    /**
     * The persisted front of an NPC kingdom, assigning its seeded stance the first time it is seen.
     * Empty for player kingdoms or when the war store is full.
     */
    public static Optional<WarFront> front(MinecraftServer server, UUID kingdom) {
        if (!NpcKingdomSavedData.get(server).isNpc(kingdom)) return Optional.empty();
        WarSavedData data = WarSavedData.get(server);
        var existing = data.front(kingdom);
        if (existing.isPresent()) return existing;
        KingdomStance stance = WarPolicy.stance(server.overworld().getSeed(), kingdom, WarConfig.HOSTILE_CHANCE.get());
        WarFront front = WarFront.initial(stance);
        if (stance == KingdomStance.HOSTILE) {
            long now = server.overworld().getGameTime();
            front = new WarFront(stance, WarConfig.INITIAL_STRENGTH.get(), now + WarConfig.FIRST_RAID_DELAY.get(), 0, 0, null, 0, false);
        }
        return data.putFront(kingdom, front) ? Optional.of(front) : Optional.empty();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!enabled()) return;
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (now % WarConfig.CHECK_INTERVAL.get() != 0) return;
        try {
            reconcileCampaigns(server);
            if (level.getDifficulty() == Difficulty.PEACEFUL) return;
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer player : level.players()) if (eligible(player)) players.add(player);
            if (players.isEmpty()) return;
            int index = Math.floorMod(CURSORS.getOrDefault(server, 0), players.size());
            CURSORS.put(server, (index + 1) % players.size());
            check(level, players.get(index));
        } catch (RuntimeException failure) {
            // A war check is optional flavour; it must never break the server tick.
            LOGGER.error("Living Kingdoms war check failed", failure);
        }
    }

    private static void check(ServerLevel level, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        String dimension = level.dimension().location().toString();
        BlockPos at = player.blockPosition();
        Optional<Settlement> here = SettlementSavedData.get(server).alliedAt(dimension, at.getX(), at.getZ());
        if (here.isEmpty()) return;
        Settlement settlement = here.get();
        long now = server.overworld().getGameTime();
        if (NpcKingdomSavedData.get(server).isNpc(settlement.id())) {
            var front = front(server, settlement.id());
            if (front.isEmpty() || !front.get().hostile()) return;
            if (WarConfig.WAR_STANDARDS.get() && !front.get().standardsPlaced()) {
                WarStandards.place(level, settlement);
                WarSavedData.get(server).putFront(settlement.id(), front.get().withStandardsPlaced());
            }
            if (WarConfig.GARRISON_ENABLED.get()) WarbandSpawner.garrison(level, settlement, player, now);
            return;
        }
        raid(level, settlement, now, false);
    }

    /**
     * Launches a raid on a player kingdom from the nearest hostile kingdom in range. {@code force} ignores the
     * raider's cooldown (operator command). Returns the attacking kingdom when a warband was placed.
     */
    public static Optional<Settlement> raid(ServerLevel level, Settlement target, long now, boolean force) {
        MinecraftServer server = level.getServer();
        if (NpcKingdomSavedData.get(server).isNpc(target.id()) || !target.faction().isAllied()) return Optional.empty();
        WarSavedData data = WarSavedData.get(server);
        if (data.hasCampaignAgainst(target.id()) || DefenseService.current(server, target.id()).isPresent()) return Optional.empty();
        Optional<Settlement> attacker = nearestHostile(server, target, WarConfig.RAID_RANGE.get(), now, force);
        if (attacker.isEmpty()) return Optional.empty();
        WarFront front = front(server, attacker.get().id()).orElseThrow();
        var party = WarbandSpawner.raid(level, target, attacker.get(), front.strength());
        if (party.isEmpty()) return Optional.empty();
        data.addCampaign(party.get().id(), new WarSavedData.Campaign(attacker.get().id(), target.id(), now));
        data.putFront(attacker.get().id(), front.raidLaunched(now, WarPolicy.raidCooldown(WarConfig.RAID_COOLDOWN.get(), front.strength())));
        notifyInside(level, target, Component.translatable("war.livingkingdoms.raid_launched",
                dev.livingkingdoms.ui.VillageNames.display(attacker.get()), dev.livingkingdoms.npckingdom.NpcKingdoms.ruler(server, attacker.get().id()),
                target.name()));
        return attacker;
    }

    private static Optional<Settlement> nearestHostile(MinecraftServer server, Settlement target, int range, long now, boolean force) {
        SettlementSavedData settlements = SettlementSavedData.get(server);
        Settlement best = null;
        long bestDistance = Long.MAX_VALUE;
        for (UUID id : NpcKingdomSavedData.get(server).ids()) {
            var candidate = settlements.get(id).orElse(null);
            if (candidate == null || !candidate.territory().dimension().equals(target.territory().dimension())) continue;
            long dx = (long) candidate.territory().x() - target.territory().x();
            long dz = (long) candidate.territory().z() - target.territory().z();
            if (!WarPolicy.withinRange(dx, dz, range)) continue;
            var front = front(server, id).orElse(null);
            if (front == null || !front.hostile() || (!force && !front.raidReady(now))) continue;
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        return Optional.ofNullable(best);
    }

    /** Campaigns whose warband vanished without a recorded defeat count as unanswered raids. */
    private static void reconcileCampaigns(MinecraftServer server) {
        WarSavedData data = WarSavedData.get(server);
        if (data.campaigns().isEmpty()) return;
        EncounterSavedData encounters = EncounterSavedData.get(server);
        for (var entry : data.campaigns().entrySet()) {
            var party = encounters.get(entry.getKey());
            if (party.isPresent() && party.get().state() == dev.livingkingdoms.encounter.domain.PartyState.ALIVE) continue;
            data.removeCampaign(entry.getKey());
            data.front(entry.getValue().kingdom()).ifPresent(front -> data.putFront(entry.getValue().kingdom(), front.raidUnanswered()));
        }
        for (UUID kingdom : data.frontIds()) {
            var front = data.front(kingdom).orElseThrow();
            if (front.garrisonParty() == null) continue;
            var garrison = encounters.get(front.garrisonParty());
            if (garrison.isEmpty() || garrison.get().state() != dev.livingkingdoms.encounter.domain.PartyState.ALIVE)
                data.putFront(kingdom, front.garrisonCleared(false, server.overworld().getGameTime(), WarConfig.GARRISON_COOLDOWN.get()));
        }
    }

    /**
     * Called once when a tracked party is defeated. Updates the war front it belonged to, then pays the
     * bounty and renown to its eligible participants.
     */
    public static void onPartyDefeated(ServerLevel level, HostileParty party, Set<UUID> participants, BlockPos position) {
        MinecraftServer server = level.getServer();
        int strength = 1;
        UUID capitulated = null;
        if (party.type() == PartyType.WARBAND && enabled()) {
            WarSavedData data = WarSavedData.get(server);
            long now = server.overworld().getGameTime();
            var campaign = data.removeCampaign(party.id());
            if (campaign.isPresent()) {
                var front = data.front(campaign.get().kingdom());
                if (front.isPresent()) {
                    strength = front.get().strength();
                    data.putFront(campaign.get().kingdom(), front.get().raidRepelled());
                }
            }
            var owner = data.garrisonOwner(party.id());
            if (owner.isPresent()) {
                var front = data.front(owner.get()).orElseThrow();
                strength = front.strength();
                if (front.capitulatesOnGarrisonDefeat() && party.rewardEligible() && !participants.isEmpty()) capitulated = owner.get();
                data.putFront(owner.get(), capitulated != null ? front.capitulated(now)
                        : front.garrisonCleared(true, now, WarConfig.GARRISON_COOLDOWN.get()));
            }
        }
        if (!party.rewardEligible() || participants.isEmpty()) return;
        RewardService.payBounty(level, party, strength, position);
        RewardService.awardRenown(server, party, participants);
        if (capitulated != null) announceCapitulation(level, capitulated, participants);
    }

    private static void announceCapitulation(ServerLevel level, UUID kingdom, Set<UUID> participants) {
        MinecraftServer server = level.getServer();
        // Liberation also ends any raid this kingdom still has in the field; it is not counted against it.
        WarSavedData data = WarSavedData.get(server);
        for (var entry : data.campaigns().entrySet()) if (entry.getValue().kingdom().equals(kingdom)) data.removeCampaign(entry.getKey());
        var settlement = SettlementSavedData.get(server).get(kingdom).orElse(null);
        Component name = settlement == null ? Component.literal("?") : Component.literal(dev.livingkingdoms.ui.VillageNames.display(settlement));
        Component message = Component.translatable("war.livingkingdoms.capitulated", name, dev.livingkingdoms.npckingdom.NpcKingdoms.ruler(server, kingdom));
        RewardService.awardBonusRenown(server, participants, RewardService.CAPITULATION_RENOWN);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) player.displayClientMessage(message, false);
        LOGGER.info("Hostile NPC kingdom {} capitulated", kingdom);
    }

    private static void notifyInside(ServerLevel level, Settlement settlement, Component message) {
        for (ServerPlayer player : level.players())
            if (player.isAlive() && !player.isSpectator() && settlement.territory().contains(settlement.territory().dimension(),
                    player.blockPosition().getX(), player.blockPosition().getZ()))
                player.displayClientMessage(message, false);
    }

    private static boolean eligible(ServerPlayer player) {
        if (player instanceof FakePlayer || !player.isAlive() || player.isSpectator()) return false;
        GameType mode = player.gameMode.getGameModeForPlayer();
        return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
    }
}

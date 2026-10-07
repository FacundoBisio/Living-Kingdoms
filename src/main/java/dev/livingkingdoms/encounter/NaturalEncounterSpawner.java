package dev.livingkingdoms.encounter;

import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Bounded server proposals in currently active terrain; no chunk tickets, entity scans or terrain writes. */
public final class NaturalEncounterSpawner {
    private static final int MAX_CANDIDATES = 3;
    private static final int GROUP_MARGIN = 6;
    private static final int ALLIED_BUFFER = 16;
    // Server-thread only. Weak level keys cannot retain a closed world, and no state crosses saves.
    private static final Map<ServerLevel, Integer> PLAYER_CURSORS = new WeakHashMap<>();

    private NaturalEncounterSpawner() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !KingdomConfig.NATURAL_ENABLED.get()
                || level.getGameTime() % KingdomConfig.NATURAL_CHECK_INTERVAL.get() != 0 || !event.hasTime()) return;
        List<ServerPlayer> players = level.players().stream().filter(NaturalEncounterSpawner::eligiblePlayer).toList();
        if (players.isEmpty()) return;
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        if (level.getGameTime() < data.nextNaturalAttempt(dimension(level))) return;
        int index = Math.floorMod(PLAYER_CURSORS.getOrDefault(level, 0), players.size());
        PLAYER_CURSORS.put(level, (index + 1) % players.size());
        attemptNear(players.get(index));
    }

    /** One cooldown-protected proposal near one participating player; at most three sites are inspected. */
    public static Optional<HostileParty> attemptNear(ServerPlayer player) {
        Optional<EncounterSavedData> attempt = beginAttempt(player);
        if (attempt.isEmpty()) return Optional.empty();
        ServerLevel level = player.serverLevel();
        int minimum = KingdomConfig.NATURAL_MIN_PLAYER_DISTANCE.get() + GROUP_MARGIN;
        int maximum = KingdomConfig.NATURAL_MAX_PLAYER_DISTANCE.get();
        if (minimum > maximum || !hasCapacity(attempt.get(), level, player.blockPosition())) return Optional.empty();
        PartyType type = NaturalEncounterPolicy.typeForTime(level.isNight(), level.random.nextInt(4));
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            int radius = minimum + level.random.nextInt(maximum - minimum + 1);
            BlockPos proposed = player.blockPosition().offset((int) Math.round(Math.cos(angle) * radius), 0,
                    (int) Math.round(Math.sin(angle) * radius));
            Optional<HostileParty> spawned = spawnCandidate(player, proposed, type, attempt.get());
            if (spawned.isPresent()) return spawned;
        }
        return Optional.empty();
    }

    /** Same natural rules for a deterministic site proposed by future regional spawning policies. */
    public static Optional<HostileParty> attemptAt(ServerPlayer player, BlockPos proposed, PartyType type) {
        return beginAttempt(player).flatMap(data -> spawnCandidate(player, proposed, type, data));
    }

    private static Optional<EncounterSavedData> beginAttempt(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!eligiblePlayer(player)) return Optional.empty();
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        long now = level.getGameTime();
        if (!NaturalEncounterPolicy.canAttempt(KingdomConfig.NATURAL_ENABLED.get(),
                level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING),
                level.getDifficulty() == Difficulty.PEACEFUL, level.dimension() == Level.OVERWORLD,
                now, data.nextNaturalAttempt(dimension(level)))) return Optional.empty();
        // Every actual attempt spends its cooldown, including blocked, unloaded and crowded proposals.
        data.scheduleNaturalAttempt(dimension(level), NaturalEncounterPolicy.nextAttempt(now, KingdomConfig.NATURAL_COOLDOWN.get()));
        return Optional.of(data);
    }

    private static Optional<HostileParty> spawnCandidate(ServerPlayer anchor, BlockPos proposed,
                                                        PartyType type, EncounterSavedData data) {
        ServerLevel level = anchor.serverLevel();
        if (!withinSpawnDistance(anchor, proposed) || !hasCapacity(data, level, anchor.blockPosition())
                || !hasCapacity(data, level, proposed) || !activeFootprint(level, proposed)) return Optional.empty();
        int clearance = KingdomConfig.NATURAL_MIN_PLAYER_DISTANCE.get() + GROUP_MARGIN;
        for (ServerPlayer player : level.players()) {
            if (!farFromPlayer(player, proposed, clearance)) return Optional.empty();
        }
        // The explicit anchor also covers callers whose test/server player has not joined level.players().
        if (!farFromPlayer(anchor, proposed, clearance)) return Optional.empty();
        int spacing = KingdomConfig.NATURAL_MIN_DISTANCE.get();
        for (HostileParty party : data.activeNearby(dimension(level), proposed.getX(), proposed.getZ(), spacing)) {
            if (!NaturalEncounterPolicy.separated(proposed.getX(), proposed.getZ(), party.origin().x(), party.origin().z(), spacing)) {
                return Optional.empty();
            }
        }
        if (SettlementSavedData.get(level.getServer()).nearAlliedTerritory(dimension(level), proposed.getX(), proposed.getZ(),
                ALLIED_BUFFER + GROUP_MARGIN)) return Optional.empty();
        if (type == PartyType.UNDEAD_HORDE && !darkAtNight(level, proposed)) return Optional.empty();
        EncounterSpawner.Result result = EncounterSpawner.spawnAt(level, proposed, type, false, true);
        return Optional.ofNullable(result.party());
    }

    private static boolean hasCapacity(EncounterSavedData data, ServerLevel level, BlockPos position) {
        int nearby = data.activeNearby(dimension(level), position.getX(), position.getZ(), KingdomConfig.NATURAL_REGION_RADIUS.get()).size();
        return NaturalEncounterPolicy.hasCapacity(nearby, KingdomConfig.NATURAL_MAX_NEARBY.get(),
                data.trackedPartyCount(), KingdomConfig.NATURAL_MAX_TRACKED.get());
    }

    private static boolean withinSpawnDistance(ServerPlayer player, BlockPos position) {
        double dx = player.getX() - (position.getX() + 0.5);
        double dz = player.getZ() - (position.getZ() + 0.5);
        double squared = dx * dx + dz * dz;
        int minimum = KingdomConfig.NATURAL_MIN_PLAYER_DISTANCE.get() + GROUP_MARGIN;
        int maximum = KingdomConfig.NATURAL_MAX_PLAYER_DISTANCE.get();
        return minimum <= maximum && squared >= (double) minimum * minimum && squared <= (double) maximum * maximum;
    }

    private static boolean farFromPlayer(ServerPlayer player, BlockPos position, int minimum) {
        double dx = player.getX() - (position.getX() + 0.5);
        double dz = player.getZ() - (position.getZ() + 0.5);
        return dx * dx + dz * dz >= (double) minimum * minimum;
    }

    private static boolean activeFootprint(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 4) >> 4; x <= (center.getX() + 4) >> 4; x++) {
            for (int z = (center.getZ() - 4) >> 4; z <= (center.getZ() + 4) >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)
                        || !level.isPositionEntityTicking(new BlockPos(x * 16 + 8, center.getY(), z * 16 + 8))) return false;
            }
        }
        return true;
    }

    private static boolean darkAtNight(ServerLevel level, BlockPos center) {
        if (!level.isNight()) return false;
        // All eight possible feet positions, rather than only the darkest center block.
        for (int x = -3; x <= 3; x += 3) {
            for (int z = -3; z <= 3; z += 3) {
                if (x == 0 && z == 0) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX() + x, center.getZ() + z);
                BlockPos feet = new BlockPos(center.getX() + x, y, center.getZ() + z);
                if (!NaturalEncounterPolicy.undeadAllowed(true, level.getBrightness(LightLayer.BLOCK, feet))) return false;
            }
        }
        return true;
    }

    private static boolean eligiblePlayer(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        return player.isAlive() && !player.isSpectator() && (mode == GameType.SURVIVAL || mode == GameType.ADVENTURE);
    }

    private static String dimension(ServerLevel level) { return level.dimension().location().toString(); }
}

package dev.livingkingdoms.npckingdom;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.config.NpcKingdomConfig;
import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.GenerationDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.OptionalLong;
import java.util.WeakHashMap;

/**
 * Natural NPC kingdom trigger. Newly generated Overworld chunks only enqueue their region cell; the
 * server tick later attempts at most one queued cell per interval, using loaded chunks exclusively.
 * Runtime cost: one hash lookup per new chunk, and per interval one bounded chunk-presence check of the
 * territory square plus one settlement overlap pass, followed on success by the existing one-tick
 * village placement (roughly 5k-15k block writes).
 */
public final class NpcKingdomService {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int PLAYER_CLEARANCE = 64;
    // Server-thread only and transient; attempted cells are the persistent source of truth.
    private static final Map<MinecraftServer, NpcKingdomQueue> QUEUES = new WeakHashMap<>();

    public enum Outcome { GENERATED, LIMIT_REACHED, ALREADY_ATTEMPTED, UNSUITABLE, TOO_CLOSE, WORLD_BORDER, PLAYER_NEARBY,
        UNLOADED_RETRY, UNLOADED_ABANDONED, FAILED }

    private NpcKingdomService() {}

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !KingdomConfig.SPEC.isLoaded() || !NpcKingdomConfig.ENABLED.get()) return;
        // The chunk may not be FULL yet: keep only its position and touch the world later on the server thread.
        ChunkPos pos = event.getChunk().getPos();
        long cell = NpcKingdomPolicy.cell(pos.getMinBlockX(), pos.getMinBlockZ(), NpcKingdomConfig.CELL_SIZE.get()).key();
        MinecraftServer server = level.getServer();
        server.execute(() -> enqueue(server, cell));
    }

    /** Server thread. Returns whether the cell was newly queued. */
    public static boolean enqueue(MinecraftServer server, long cell) {
        if (!server.isSameThread()) throw new IllegalStateException("NPC kingdom queue belongs to the server thread");
        if (!KingdomConfig.SPEC.isLoaded() || !NpcKingdomConfig.ENABLED.get()) return false;
        NpcKingdomSavedData data = NpcKingdomSavedData.get(server);
        if (data.count() >= NpcKingdomConfig.MAX_KINGDOMS.get() || data.attempted(cell)) return false;
        if (!NpcKingdomPolicy.selected(server.overworld().getSeed(), NpcKingdomPolicy.Cell.fromKey(cell), NpcKingdomConfig.CHANCE.get()))
            return false;
        return queue(server).offer(cell);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!KingdomConfig.SPEC.isLoaded() || !NpcKingdomConfig.ENABLED.get()) return;
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (now % NpcKingdomConfig.CHECK_INTERVAL.get() != 0) return;
        NpcKingdomQueue queue = QUEUES.get(server);
        if (queue == null || queue.size() == 0) return;
        if (now < NpcKingdomSavedData.get(server).nextAttemptAt() || !explorerPresent(level)) return;
        OptionalLong cell = queue.poll();
        if (cell.isEmpty()) return;
        Outcome outcome = attempt(level, cell.getAsLong());
        if (outcome == Outcome.UNLOADED_RETRY || outcome == Outcome.PLAYER_NEARBY) queue.offer(cell.getAsLong());
    }

    /**
     * One bounded natural attempt for a region cell. Never loads chunks: every block or heightmap access is
     * preceded by {@code hasChunk} for the whole territory square. Records the cell unless it may be retried.
     */
    public static Outcome attempt(ServerLevel level, long cellKey) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("NPC kingdom generation belongs to the server thread");
        NpcKingdomSavedData data = NpcKingdomSavedData.get(server);
        if (data.attempted(cellKey)) return Outcome.ALREADY_ATTEMPTED;
        if (data.count() >= NpcKingdomConfig.MAX_KINGDOMS.get()) return Outcome.LIMIT_REACHED;
        int radius = KingdomConfig.SETTLEMENT_RADIUS.get();
        int cellSize = NpcKingdomConfig.CELL_SIZE.get();
        if (cellSize - 2 * NpcKingdomPolicy.margin(radius) < 1) { data.markAttempted(cellKey); return Outcome.UNSUITABLE; }
        long seed = level.getSeed();
        NpcKingdomPolicy.Cell cell = NpcKingdomPolicy.Cell.fromKey(cellKey);
        NpcKingdomPolicy.Center center = NpcKingdomPolicy.center(seed, cell, cellSize, radius);
        int x = center.x(), z = center.z();
        var border = level.getWorldBorder();
        if (!border.isWithinBounds(new BlockPos(x - radius, 0, z - radius)) || !border.isWithinBounds(new BlockPos(x + radius, 0, z + radius))) {
            data.markAttempted(cellKey); return Outcome.WORLD_BORDER;
        }
        if (!squareLoaded(level, x, z, radius)) return unloaded(data, cellKey);
        String dimension = level.dimension().location().toString();
        SettlementSavedData settlements = SettlementSavedData.get(server);
        int minDistance = NpcKingdomConfig.MIN_DISTANCE.get();
        // Indexed allied lookup first; then one bounded linear overlap pass (once per interval at most) also
        // covers non-allied records and the other settlement's own radius.
        if (settlements.nearestAllied(dimension, x, z, Math.min(minDistance, SettlementSavedData.MAX_NEAREST_DISTANCE)).isPresent()
                || settlements.overlaps(new Territory(dimension, x, 0, z, minDistance))) {
            data.markAttempted(cellKey); return Outcome.TOO_CLOSE;
        }
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - x, dz = player.getZ() - z;
            if (dx * dx + dz * dz <= (double) PLAYER_CLEARANCE * PLAYER_CLEARANCE) return Outcome.PLAYER_NEARBY;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        SettlementGenerator.Result result;
        try {
            result = new SettlementGenerator().generateAt(level, new BlockPos(x, y, z));
        } catch (RuntimeException exception) {
            // generateAt rolls its own placement transaction back; never fail the server tick.
            LOGGER.error("Natural NPC kingdom generation failed in cell {},{}", cell.x(), cell.z(), exception);
            data.markAttempted(cellKey);
            return Outcome.FAILED;
        }
        if (!result.successful()) {
            if (result.failure() == SettlementGenerator.Failure.NO_SAFE_SITE && unloadedRejection(result.diagnostics()))
                return unloaded(data, cellKey);
            data.markAttempted(cellKey);
            return Outcome.FAILED;
        }
        long now = server.overworld().getGameTime();
        if (!data.register(result.settlement().id(), NpcKingdomPolicy.ruler(seed, cell), now))
            LOGGER.warn("NPC kingdom registry rejected generated settlement {}", result.settlement().id());
        data.markAttempted(cellKey);
        data.setNextAttemptAt(now + NpcKingdomConfig.COOLDOWN.get());
        LOGGER.info("Generated independent NPC kingdom {} at {}, {}", result.settlement().name(), result.settlement().territory().x(),
                result.settlement().territory().z());
        return Outcome.GENERATED;
    }

    /** Registers an explicitly generated settlement (debug command) as NPC-ruled. */
    public static boolean registerExplicit(ServerLevel level, dev.livingkingdoms.settlement.domain.Settlement settlement) {
        var data = NpcKingdomSavedData.get(level.getServer());
        int cellSize = NpcKingdomConfig.CELL_SIZE.get();
        var cell = NpcKingdomPolicy.cell(settlement.territory().x(), settlement.territory().z(), cellSize);
        if (!data.register(settlement.id(), NpcKingdomPolicy.ruler(level.getSeed(), cell), level.getServer().overworld().getGameTime()))
            return false;
        data.markAttempted(cell.key());
        return true;
    }

    public static int queued(MinecraftServer server) {
        NpcKingdomQueue queue = QUEUES.get(server);
        return queue == null ? 0 : queue.size();
    }

    private static NpcKingdomQueue queue(MinecraftServer server) {
        return QUEUES.computeIfAbsent(server, ignored -> new NpcKingdomQueue());
    }

    private static Outcome unloaded(NpcKingdomSavedData data, long cellKey) {
        return data.retryUnloaded(cellKey) ? Outcome.UNLOADED_RETRY : Outcome.UNLOADED_ABANDONED;
    }

    private static boolean unloadedRejection(GenerationDiagnostics.Summary summary) {
        return summary.centerFailures().getOrDefault(GenerationDiagnostics.Rejection.UNLOADED_CHUNKS, 0) > 0
                || summary.plotFailures().getOrDefault(GenerationDiagnostics.Rejection.UNLOADED_CHUNKS, 0) > 0;
    }

    /** Presence-only check; {@code hasChunk} never loads or generates a chunk. */
    static boolean squareLoaded(ServerLevel level, int x, int z, int radius) {
        var chunks = level.getChunkSource();
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++)
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++)
                if (!chunks.hasChunk(cx, cz)) return false;
        return true;
    }

    /** Natural generation only runs while a real (non-fake, non-spectator) player is in the Overworld. */
    private static boolean explorerPresent(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (player instanceof FakePlayer || player.isSpectator()) continue;
            return true;
        }
        return false;
    }
}

package dev.livingkingdoms.settlement;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.SettlementSitePlanner;
import dev.livingkingdoms.structure.SettlementTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server service shared by debug generation and future natural-generation callers. */
public final class SettlementGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private final SettlementSitePlanner planner = new SettlementSitePlanner();

    public Result generateNear(ServerLevel level, BlockPos playerPosition) {
        return generate(level, playerPosition, true);
    }

    /** Target a candidate center; future worldgen must schedule this on the server thread. */
    public Result generateAt(ServerLevel level, BlockPos candidateCenter) {
        return generate(level, candidateCenter, false);
    }

    private Result generate(ServerLevel level, BlockPos position, boolean search) {
        SettlementSavedData data = SettlementSavedData.get(level.getServer());
        // Load the relationship store before changing terrain, including guarded corrupt-save handling.
        QuestSavedData.get(level.getServer());
        SettlementTemplate template;
        try {
            template = SettlementTemplate.load(level);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not load allied settlement template", exception);
            return Result.failed(Failure.TEMPLATE_UNAVAILABLE);
        }
        int slope = KingdomConfig.GENERATION_MAX_SLOPE.get();
        int radius = KingdomConfig.SETTLEMENT_RADIUS.get();
        Optional<SettlementSitePlanner.Plan> found = search
                ? planner.findNear(level, data, template, position, KingdomConfig.GENERATION_SEARCH_RANGE.get(), slope, radius)
                : planner.at(level, data, template, position, slope, radius);
        if (found.isEmpty()) return Result.failed(Failure.NO_SAFE_SITE);
        var plan = found.orElseThrow();
        List<Snapshot> before = new ArrayList<>();
        for (BlockPos pos : plan.supports()) before.add(new Snapshot(pos, level.getBlockState(pos)));
        for (BlockPos pos : BlockPos.betweenClosed(plan.origin(), plan.high())) {
            before.add(new Snapshot(pos.immutable(), level.getBlockState(pos)));
        }
        try {
            // Raise the foundation above gentle slopes instead of excavating existing ground.
            for (BlockPos pos : plan.supports()) {
                if (!level.getBlockState(pos).equals(Blocks.COBBLESTONE.defaultBlockState())
                        && !level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), FLAGS)) {
                    throw new IllegalStateException("Foundation placement refused at " + pos);
                }
            }
            var settings = SettlementTemplate.settings().setBoundingBox(BoundingBox.fromCorners(plan.origin(), plan.high()));
            if (!template.template().placeInWorld(level, plan.origin(), plan.origin(), settings, level.random, FLAGS)) {
                throw new IllegalStateException("Template placement refused");
            }
            // Vanilla can return true even if individual writes fail. Confirm every exported state.
            for (var block : template.blocks()) {
                if (!level.getBlockState(plan.origin().offset(block.pos())).equals(block.state())) {
                    throw new IllegalStateException("Incomplete template placement at " + block.pos());
                }
            }
            Settlement settlement = Settlement.founding(UUID.randomUUID(), plan.territory(), KingdomConfig.INITIAL_POPULATION.get());
            data.add(settlement);
            // The physical settlement already exists; an NPC failure must not roll back its blocks alone.
            try {
                if (NpcService.ensureMayor(level, settlement).isEmpty()) {
                    LOGGER.warn("Settlement {} generated, but no loaded safe Mayor location was available", settlement.id());
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Settlement {} generated, but Mayor association failed", settlement.id(), exception);
            }
            return new Result(settlement, null);
        } catch (RuntimeException exception) {
            for (int i = before.size() - 1; i >= 0; i--) {
                Snapshot snapshot = before.get(i);
                level.setBlock(snapshot.position(), snapshot.state(), FLAGS);
            }
            LOGGER.error("Settlement placement failed at {}; original blocks restored", plan.origin(), exception);
            return Result.failed(Failure.PLACEMENT_FAILED);
        }
    }

    private record Snapshot(BlockPos position, BlockState state) {}

    public enum Failure { TEMPLATE_UNAVAILABLE, NO_SAFE_SITE, PLACEMENT_FAILED }

    public record Result(Settlement settlement, Failure failure) {
        public Result {
            if ((settlement == null) == (failure == null)) throw new IllegalArgumentException("Result requires success or failure");
        }
        static Result failed(Failure failure) { return new Result(null, failure); }
        public boolean successful() { return settlement != null; }
    }
}

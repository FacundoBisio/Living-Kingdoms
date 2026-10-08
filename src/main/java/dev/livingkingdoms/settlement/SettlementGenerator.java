package dev.livingkingdoms.settlement;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.npc.NpcEstablishment;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementOrigin;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.ArchitectureStyle;
import dev.livingkingdoms.structure.BuildingCatalog;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.GenerationDiagnostics;
import dev.livingkingdoms.structure.SettlementLayout;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import dev.livingkingdoms.structure.SettlementLayoutPlanner;
import dev.livingkingdoms.structure.SettlementPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared explicit generation service. No tick hook, natural spawning, or chunk tickets. */
public final class SettlementGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final SettlementLayoutPlanner planner = new SettlementLayoutPlanner();

    public Result generateNear(ServerLevel level, BlockPos playerPosition) { return generate(level, playerPosition, true, false); }
    public Result generateAt(ServerLevel level, BlockPos center) { return generate(level, center, false, false); }

    /** Plaza is four blocks north of the player, keeping the caller outside the raised core footprint. */
    public Result generateHere(ServerLevel level, BlockPos playerPosition) { return generate(level, playerPosition, false, true); }

    /** Survival founding uses the same planner and applicator, but requires working NPC infrastructure. */
    public Result foundHere(ServerPlayer player, BlockPos position) {
        return generate(player.serverLevel(), position, false, true, player);
    }

    private Result generate(ServerLevel level, BlockPos position, boolean search, boolean relaxed) {
        return generate(level, position, search, relaxed, null);
    }

    private Result generate(ServerLevel level, BlockPos position, boolean search, boolean relaxed, ServerPlayer founder) {
        SettlementSavedData data = SettlementSavedData.get(level.getServer());
        QuestSavedData.get(level.getServer());
        GenerationDiagnostics diagnostics = new GenerationDiagnostics();
        BuildingCatalog catalog;
        try { catalog = BuildingCatalog.load(level, ArchitectureStyle.PLAINS); }
        catch (RuntimeException exception) {
            LOGGER.error("Could not load settlement modules", exception);
            return Result.failed(Failure.TEMPLATE_UNAVAILABLE, diagnostics);
        }
        Optional<SettlementLayout> found = relaxed ? planner.findHere(level, data, catalog, position, KingdomConfig.SETTLEMENT_RADIUS.get(), diagnostics) : search
                ? planner.findNear(level, data, catalog, position, KingdomConfig.GENERATION_SEARCH_RANGE.get(),
                        KingdomConfig.SETTLEMENT_RADIUS.get(), diagnostics)
                : planner.at(level, data, catalog, position, KingdomConfig.SETTLEMENT_RADIUS.get(), relaxed, diagnostics);
        if (found.isEmpty()) return Result.failed(Failure.NO_SAFE_SITE, diagnostics);
        SettlementLayout plan = found.orElseThrow();
        if (founder != null && plan.before().keySet().stream().anyMatch(pos -> !level.mayInteract(founder,pos)))
            return Result.failed(Failure.PROTECTED_AREA, diagnostics);
        Settlement settlement = founder == null
                ? Settlement.founding(UUID.randomUUID(), plan.territory(), KingdomConfig.INITIAL_POPULATION.get())
                : Settlement.established(UUID.randomUUID(), plan.territory(), KingdomConfig.INITIAL_POPULATION.get(), SettlementOrigin.FOUNDED, founder.getUUID());
        SettlementLayoutMetadata metadata = SettlementLayoutMetadata.from(plan);
        var before = founder == null ? java.util.List.<net.neoforged.neoforge.common.util.BlockSnapshot>of()
                : EstablishmentPlacementEvents.capture(level,plan.before().keySet());
        try (SettlementPlacement transaction = SettlementPlacement.apply(level, plan)) {
            data.add(settlement, metadata);
            if (founder != null) {
                EstablishmentPlacementEvents.validate(founder,before);
                try (var npcs = NpcEstablishment.open(level, settlement, java.util.List.of())) {
                    npcs.requireInitialResidents();
                    npcs.commit();
                }
            }
            transaction.commit();
        } catch (RuntimeException exception) {
            if (data.get(settlement.id()).isPresent()) data.rollbackEstablishment(settlement);
            if (exception instanceof EstablishmentPlacementEvents.Rejected) return Result.failed(Failure.PROTECTED_AREA,diagnostics);
            LOGGER.error("Settlement placement failed at {}; transaction rolled back", position, exception);
            return Result.failed(Failure.PLACEMENT_FAILED, diagnostics);
        }
        if (founder == null) try {
            var mayor = NpcService.ensureMayor(level, settlement);
            if (mayor.isEmpty()) LOGGER.warn("No safe loaded Mayor location for {}", settlement.id());
            else NpcService.spawnInitialResidents(level, settlement, mayor.orElseThrow());
        } catch (RuntimeException exception) { LOGGER.error("Mayor association failed for {}", settlement.id(), exception); }
        return new Result(settlement, null, diagnostics.summary());
    }

    /** Future growth API; no housing capacity or citizen behavior is attached to this operation. */
    public Optional<SettlementLayout> planBuildingAddition(ServerLevel level, UUID settlementId, BuildingKind kind,
                                                         GenerationDiagnostics diagnostics) {
        SettlementSavedData data = SettlementSavedData.get(level.getServer());
        Settlement settlement = data.get(settlementId).orElseThrow(() -> new IllegalArgumentException("Unknown settlement"));
        var metadata = data.layout(settlementId).orElseThrow(() -> new IllegalArgumentException("Legacy settlement has no reserved layout; survey it first"));
        if (!settlement.faction().isAllied() || !settlement.territory().dimension().equals(level.dimension().location().toString()))
            throw new IllegalArgumentException("Building additions require an allied settlement in this dimension");
        Map<BlockPos, BlockState> paths = new LinkedHashMap<>();
        metadata.paths().forEach(pos -> paths.put(pos, Blocks.DIRT_PATH.defaultBlockState()));
        return planner.planAddition(level, settlement.territory(), BuildingCatalog.load(level, metadata.style()), kind,
                metadata.buildings().stream().map(SettlementLayoutMetadata.Building::bounds).toList(), metadata.ports(), paths, diagnostics);
    }

    public void applyBuildingAddition(ServerLevel level, UUID settlementId, SettlementLayout addition) {
        SettlementSavedData data = SettlementSavedData.get(level.getServer());
        Settlement settlement = data.get(settlementId).orElseThrow();
        if (!settlement.faction().isAllied() || !addition.territory().equals(settlement.territory()) || addition.buildings().size() != 1
                || addition.buildings().getFirst().module().kind() == BuildingKind.CORE)
            throw new IllegalArgumentException("Invalid addition plan");
        var updated = data.layout(settlementId).orElseThrow().append(addition);
        updated.validate(settlement.territory());
        try (SettlementPlacement transaction = SettlementPlacement.apply(level, addition)) {
            data.updateLayout(settlementId, updated);
            transaction.commit();
        }
    }

    public enum Failure { TEMPLATE_UNAVAILABLE, NO_SAFE_SITE, PLACEMENT_FAILED, PROTECTED_AREA }
    public record Result(Settlement settlement, Failure failure, GenerationDiagnostics.Summary diagnostics) {
        public Result {
            if ((settlement == null) == (failure == null)) throw new IllegalArgumentException("Result requires success or failure");
        }
        static Result failed(Failure failure, GenerationDiagnostics diagnostics) { return new Result(null, failure, diagnostics.summary()); }
        public boolean successful() { return settlement != null; }
    }
}

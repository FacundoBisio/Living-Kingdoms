package dev.livingkingdoms.settlement;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.config.SettlementEstablishmentConfig;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.npc.NpcEstablishment;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.SettlementOrigin;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.GenerationDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.UUID;

/** Synchronous server authority: there is no async gap between overlap check, commit and item consumption. */
public final class SettlementEstablishmentService {
    private static final Logger LOGGER = LogUtils.getLogger();
    public enum Mode { AUTO, CONVERT_ONLY }
    private SettlementEstablishmentService() {}

    public static Result useCharter(ServerPlayer player, InteractionHand hand, BlockPos anchor, Mode mode) {
        if (!player.serverLevel().getServer().isSameThread()) throw new IllegalStateException("Charter requires server thread");
        var stack = player.getItemInHand(hand);
        if (stack.isEmpty() || !stack.is(KingdomItems.KINGDOM_CHARTER)) return Result.failed("charter.livingkingdoms.missing");
        if (player.isSpectator() || !player.getAbilities().mayBuild || !player.canInteractWithBlock(anchor,0))
            return Result.failed("charter.livingkingdoms.out_of_reach");
        Result result;
        try { result = establish(player,anchor,mode); }
        catch (RuntimeException failure) {
            LOGGER.error("Charter establishment failed at {}",anchor,failure);
            return Result.failed("charter.livingkingdoms.failed");
        }
        if (result.successful() && !player.getAbilities().instabuild) stack.shrink(1);
        return result;
    }

    private static Result establish(ServerPlayer player, BlockPos anchor, Mode mode) {
        var level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) return Result.failed("charter.livingkingdoms.dimension");
        var data = SettlementSavedData.get(level.getServer());
        // Fail closed on unreadable quest storage before placing infrastructure or spending anything.
        QuestSavedData.get(level.getServer());
        if (data.at(level.dimension().location().toString(),anchor.getX(),anchor.getZ()).isPresent())
            return Result.failed("commands.livingkingdoms.settlement.overlap");
        VillageSurvey survey = VillageSurvey.detect(level,anchor);
        if (!survey.fullyLoaded()) return Result.failed("commands.livingkingdoms.settlement.unloaded");
        if (survey.hasSignals() || mode == Mode.CONVERT_ONLY) {
            if (!survey.valid()) return Result.failed("charter.livingkingdoms.incomplete_village",
                    survey.villagers().size(),SettlementEstablishmentConfig.MIN_VILLAGERS.get(),
                    survey.beds().size(),SettlementEstablishmentConfig.MIN_BEDS.get());
            return convert(player,survey);
        }
        var generated = new SettlementGenerator().foundHere(player,anchor.above());
        if (generated.successful()) return Result.success(generated.settlement(),generated.diagnostics());
        String key = switch (generated.failure()) {
            case NO_SAFE_SITE -> generated.diagnostics().feedbackKey();
            case TEMPLATE_UNAVAILABLE -> "commands.livingkingdoms.settlement.template_unavailable";
            case PLACEMENT_FAILED -> "charter.livingkingdoms.failed";
            case PROTECTED_AREA -> "charter.livingkingdoms.protected_area";
        };
        return new Result(null,Component.translatable(key),generated.diagnostics());
    }

    private static Result convert(ServerPlayer player, VillageSurvey survey) {
        var level = player.serverLevel();
        var data = SettlementSavedData.get(level.getServer());
        var infrastructure = ConversionInfrastructure.plan(level,survey.center());
        if (infrastructure.isEmpty()) return Result.failed("charter.livingkingdoms.no_infrastructure_space");
        var plan = infrastructure.orElseThrow();
        int radius = KingdomConfig.SETTLEMENT_RADIUS.get();
        for (BlockPos pos : survey.beds()) radius = Math.max(radius,enclosingRadius(plan.marker(),pos));
        for (BlockPos pos : survey.bells()) radius = Math.max(radius,enclosingRadius(plan.marker(),pos));
        for (var villager : survey.villagers()) radius = Math.max(radius,enclosingRadius(plan.marker(),villager.blockPosition()));
        if (radius > SettlementEstablishmentConfig.MAX_CONVERSION_RADIUS.get()) return Result.failed("charter.livingkingdoms.village_too_large");
        var territory = new Territory(level.dimension().location().toString(),plan.marker().getX(),plan.marker().getY(),plan.marker().getZ(),radius);
        if (data.overlaps(territory)) return Result.failed("commands.livingkingdoms.settlement.overlap");
        if (!level.hasChunksAt(plan.marker().offset(-radius,0,-radius),plan.marker().offset(radius,0,radius)))
            return Result.failed("commands.livingkingdoms.settlement.unloaded");
        if (!level.getWorldBorder().isWithinBounds(plan.marker().offset(-radius,0,-radius))
                || !level.getWorldBorder().isWithinBounds(plan.marker().offset(radius,0,radius)))
            return Result.failed("charter.livingkingdoms.border");
        if (!level.mayInteract(player,plan.marker()) || !level.mayInteract(player,plan.board()))
            return Result.failed("charter.livingkingdoms.protected_area");
        var settlement = Settlement.established(UUID.randomUUID(),territory,survey.villagers().size(),SettlementOrigin.CONVERTED,player.getUUID());
        var before = EstablishmentPlacementEvents.capture(level,java.util.List.of(plan.marker(),plan.board()));
        try (var placement = plan.apply(level)) {
            data.add(settlement);
            EstablishmentPlacementEvents.validate(player,before);
            try (var npcs = NpcEstablishment.open(level,settlement,survey.villagers())) { npcs.commit(); }
            placement.commit();
        } catch (RuntimeException failure) {
            if (data.get(settlement.id()).isPresent()) data.rollbackEstablishment(settlement);
            if (failure instanceof EstablishmentPlacementEvents.Rejected) return Result.failed("charter.livingkingdoms.protected_area");
            LOGGER.error("Village conversion rolled back at {}",plan.marker(),failure);
            return Result.failed("charter.livingkingdoms.failed");
        }
        return Result.success(settlement,new GenerationDiagnostics().summary());
    }

    private static int enclosingRadius(BlockPos center, BlockPos point) {
        return (int)Math.ceil(Math.hypot((double)point.getX()-center.getX(),(double)point.getZ()-center.getZ()))+8;
    }

    public record Result(Settlement settlement, Component message, GenerationDiagnostics.Summary diagnostics) {
        public boolean successful() { return settlement != null; }
        static Result failed(String key, Object... args) { return new Result(null,Component.translatable(key,args),new GenerationDiagnostics().summary()); }
        static Result success(Settlement settlement, GenerationDiagnostics.Summary diagnostics) {
            String key = settlement.provenance().origin() == SettlementOrigin.CONVERTED ? "charter.livingkingdoms.converted" : "charter.livingkingdoms.founded";
            return new Result(settlement,Component.translatable(key,dev.livingkingdoms.ui.VillageNames.display(settlement),
                    settlement.territory().x(),settlement.territory().y(),settlement.territory().z()),diagnostics);
        }
    }
}

package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.GenerationDiagnostics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.UUID;

/** Thin server-side command adapter. */
public final class SettlementCommands {
    private SettlementCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("settlement")
                        .then(Commands.literal("create").requires(source -> source.hasPermission(2))
                                .executes(SettlementCommands::create))
                        .then(Commands.literal("generate").requires(source -> source.hasPermission(2))
                                .executes(SettlementCommands::generate)
                                .then(Commands.literal("debug").executes(context -> generate(context, false, true)))
                                .then(Commands.literal("here").executes(context -> generate(context, true, false))
                                        .then(Commands.literal("debug").executes(context -> generate(context, true, true)))))
                        .then(Commands.literal("debug-layout").requires(source -> source.hasPermission(2))
                                .executes(context -> dev.livingkingdoms.structure.SettlementLayoutDebug.show(context.getSource())))
                        .then(Commands.literal("inspect").requires(source -> source.hasPermission(2)).executes(SettlementCommands::inspect))
                        .then(Commands.literal("info").executes(SettlementCommands::info)))
                .then(Commands.literal("reputation").executes(SettlementCommands::reputation)));
    }

    private static int reputation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos pos = player.blockPosition();
        var found = SettlementSavedData.get(source.getServer()).at(
                player.serverLevel().dimension().location().toString(), pos.getX(), pos.getZ());
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.not_found"));
            return 0;
        }
        Settlement settlement = found.orElseThrow();
        int reputation = QuestSavedData.get(source.getServer()).reputation(player.getUUID(), settlement.id());
        source.sendSuccess(() -> Component.translatable("quest.livingkingdoms.reputation", dev.livingkingdoms.ui.VillageNames.display(settlement), reputation), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return generate(context, false, false);
    }

    private static int generate(CommandContext<CommandSourceStack> context, boolean here, boolean debug) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        SettlementGenerator generator = new SettlementGenerator();
        SettlementGenerator.Result result = here ? generator.generateHere(player.serverLevel(), player.blockPosition())
                : generator.generateNear(player.serverLevel(), player.blockPosition());
        if (debug) {
            var summary = result.diagnostics();
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.settlement.debug.candidates",
                    summary.centersChecked(), summary.plotsChecked()), false);
            sendRejections(source, "commands.livingkingdoms.settlement.debug.centers", summary.centerFailures());
            sendRejections(source, "commands.livingkingdoms.settlement.debug.plots", summary.plotFailures());
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.settlement.debug.valid_plots", summary.bestValidPlots(), 4), false);
        }
        if (!result.successful()) {
            String key = switch (result.failure()) {
                case TEMPLATE_UNAVAILABLE -> "commands.livingkingdoms.settlement.template_unavailable";
                case NO_SAFE_SITE -> result.diagnostics().feedbackKey();
                case PLACEMENT_FAILED -> "commands.livingkingdoms.settlement.placement_failed";
                case PROTECTED_AREA -> "charter.livingkingdoms.protected_area";
            };
            source.sendFailure(Component.translatable(key));
            return 0;
        }
        Settlement settlement = result.settlement();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.settlement.generated",
                settlement.name(), settlement.id().toString(), settlement.territory().x(), settlement.territory().y(), settlement.territory().z()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static void sendRejections(CommandSourceStack source, String label,
                                       java.util.Map<GenerationDiagnostics.Rejection, Integer> failures) {
        if (failures.isEmpty()) return;
        Component message = Component.translatable(label);
        for (var reason : GenerationDiagnostics.Rejection.values()) {
            Integer count = failures.get(reason);
            if (count == null) continue;
            message = message.copy().append(Component.literal(" ")).append(Component.translatable(
                    "commands.livingkingdoms.settlement.debug." + reason.name().toLowerCase(java.util.Locale.ROOT)))
                    .append(Component.literal(": " + count + ";"));
        }
        Component output = message;
        source.sendSuccess(() -> output, false);
    }

    private static int create(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos pos = player.blockPosition();
        Territory territory = new Territory(player.serverLevel().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(), KingdomConfig.SETTLEMENT_RADIUS.get());
        SettlementSavedData data = SettlementSavedData.get(source.getServer());
        if (data.overlaps(territory)) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.overlap"));
            return 0;
        }
        Settlement settlement = Settlement.founding(UUID.randomUUID(), territory, KingdomConfig.INITIAL_POPULATION.get());
        data.add(settlement);
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.settlement.created",
                settlement.name(), settlement.id().toString()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BlockPos pos = player.blockPosition();
        Optional<Settlement> found = SettlementSavedData.get(source.getServer()).at(
                player.serverLevel().dimension().location().toString(), pos.getX(), pos.getZ());
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.not_found"));
            return 0;
        }
        Settlement settlement = found.orElseThrow();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.settlement.info",
                dev.livingkingdoms.ui.VillageNames.display(settlement),
                Component.translatable("faction.livingkingdoms." + settlement.faction().id()),
                settlement.level(), settlement.population()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int inspect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        var pos = player.blockPosition();
        var found = SettlementSavedData.get(source.getServer()).at(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ());
        if (found.isEmpty()) { source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.not_found")); return 0; }
        var settlement = found.orElseThrow();
        var provenance = settlement.provenance();
        var territory = settlement.territory();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.settlement.inspect",settlement.id().toString(),
                provenance.origin().name(),provenance.founder().map(UUID::toString).orElse("unknown"),provenance.createdAtEpochMillis(),
                provenance.kingdom().map(UUID::toString).orElse("none"),territory.x(),territory.y(),territory.z(),territory.radius()),false);
        return Command.SINGLE_SUCCESS;
    }
}

package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
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
                        .then(Commands.literal("info").executes(SettlementCommands::info))));
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
                settlement.name(), settlement.id().toString(),
                Component.translatable("faction.livingkingdoms." + settlement.faction().id()),
                settlement.level(), settlement.population()), false);
        return Command.SINGLE_SUCCESS;
    }
}

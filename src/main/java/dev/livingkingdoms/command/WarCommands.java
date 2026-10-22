package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.npckingdom.NpcKingdoms;
import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import dev.livingkingdoms.war.RewardService;
import dev.livingkingdoms.war.WarService;
import dev.livingkingdoms.war.domain.KingdomStance;
import dev.livingkingdoms.war.persistence.WarSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;

/** {@code /kingdom renown} for every player; {@code /kingdom war ...} operator tools for hostile kingdoms. */
public final class WarCommands {
    public static final int MAX_LIST_LINES = 32;
    private WarCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("renown").executes(WarCommands::renown))
                .then(Commands.literal("war").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(WarCommands::status))
                        .then(Commands.literal("raid").executes(WarCommands::raid))
                        .then(Commands.literal("stance")
                                .then(Commands.literal("hostile").executes(context -> stance(context, KingdomStance.HOSTILE)))
                                .then(Commands.literal("independent").executes(context -> stance(context, KingdomStance.INDEPENDENT))))));
    }

    private static int renown(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        RewardService.deliverPending(player);
        var ledger = WarSavedData.get(player.server).ledger(player.getUUID());
        var rank = ledger.rank();
        context.getSource().sendSuccess(() -> Component.translatable("renown.livingkingdoms.status",
                Component.translatable(rank.titleKey()), ledger.points()), false);
        rank.next().ifPresentOrElse(next -> context.getSource().sendSuccess(() -> Component.translatable("renown.livingkingdoms.next",
                        Component.translatable(next.titleKey()), next.threshold() - ledger.points()), false),
                () -> context.getSource().sendSuccess(() -> Component.translatable("renown.livingkingdoms.max"), false));
        return ledger.points();
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var server = source.getServer();
        var settlements = SettlementSavedData.get(server);
        var ids = NpcKingdomSavedData.get(server).ids();
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.list.empty"), false);
            return 0;
        }
        int shown = 0, hostile = 0;
        for (var id : ids) {
            var front = WarService.front(server, id).orElse(null);
            var settlement = settlements.get(id).orElse(null);
            if (front == null || settlement == null || !front.hostile()) continue;
            hostile++;
            if (shown >= MAX_LIST_LINES) continue;
            shown++;
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.war.entry", VillageNames.display(settlement),
                    NpcKingdoms.ruler(server, id), front.strength(), front.raidsLaunched(), front.raidsRepelled(),
                    settlement.territory().x(), settlement.territory().z()), false);
        }
        int count = hostile;
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.war.header", count, ids.size(),
                WarSavedData.get(server).campaigns().size()), false);
        return hostile;
    }

    private static int raid(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        var here = settlementAt(player);
        if (here.isEmpty() || NpcKingdomSavedData.get(source.getServer()).isNpc(here.get().id())) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.war.not_player_kingdom"));
            return 0;
        }
        var attacker = WarService.raid(player.serverLevel(), here.get(), source.getServer().overworld().getGameTime(), true);
        if (attacker.isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.war.raid_failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.war.raid_started",
                VillageNames.display(attacker.get()), here.get().name()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int stance(CommandContext<CommandSourceStack> context, KingdomStance stance) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        var here = settlementAt(player);
        var front = here.flatMap(settlement -> WarService.front(source.getServer(), settlement.id()));
        if (front.isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.war.not_npc_kingdom"));
            return 0;
        }
        WarSavedData.get(source.getServer()).putFront(here.get().id(), front.get().withStance(stance));
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.war.stance_set", VillageNames.display(here.get()),
                Component.translatable("war.livingkingdoms.stance." + stance.id())), true);
        return Command.SINGLE_SUCCESS;
    }

    private static Optional<Settlement> settlementAt(ServerPlayer player) {
        var pos = player.blockPosition();
        return SettlementSavedData.get(player.server).alliedAt(player.serverLevel().dimension().location().toString(), pos.getX(), pos.getZ());
    }
}

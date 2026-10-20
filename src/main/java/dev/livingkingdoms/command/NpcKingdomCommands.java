package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.npckingdom.NpcKingdomService;
import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator tools for independent NPC kingdoms; thin adapter over the shared generator and registry. */
public final class NpcKingdomCommands {
    public static final int MAX_LIST_LINES = 32;
    private NpcKingdomCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("npc").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("generate").executes(NpcKingdomCommands::generate))
                        .then(Commands.literal("list").executes(NpcKingdomCommands::list))));
    }

    private static int generate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        var level = player.serverLevel();
        SettlementGenerator.Result result = new SettlementGenerator().generateNear(level, player.blockPosition());
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
        if (!NpcKingdomService.registerExplicit(level, settlement)) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.npc.register_failed", VillageNames.display(settlement)));
            return 0;
        }
        String ruler = NpcKingdomSavedData.get(source.getServer()).ruler(settlement.id()).orElseThrow();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.generated", VillageNames.display(settlement), ruler,
                settlement.territory().x(), settlement.territory().y(), settlement.territory().z()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var registry = NpcKingdomSavedData.get(source.getServer());
        var settlements = SettlementSavedData.get(source.getServer());
        var ids = registry.ids();
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.list.empty"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.list.header", ids.size()), false);
        String dimension = source.getLevel().dimension().location().toString();
        var origin = source.getPosition();
        int shown = Math.min(ids.size(), MAX_LIST_LINES - 2);
        for (int i = 0; i < shown; i++) {
            var id = ids.get(i);
            String ruler = registry.ruler(id).orElse("?");
            var settlement = settlements.get(id).orElse(null);
            if (settlement == null) {
                source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.list.missing", id.toString(), ruler), false);
                continue;
            }
            var territory = settlement.territory();
            String distance = territory.dimension().equals(dimension)
                    ? Long.toString(Math.round(Math.hypot(origin.x - territory.x(), origin.z - territory.z()))) : "-";
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.list.entry", VillageNames.display(settlement), ruler,
                    territory.x(), territory.z(), distance), false);
        }
        if (ids.size() > shown) {
            int remaining = ids.size() - shown;
            source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.npc.list.more", remaining), false);
        }
        return ids.size();
    }
}

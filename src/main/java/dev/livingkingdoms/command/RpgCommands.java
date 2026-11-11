package dev.livingkingdoms.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.rpg.ChallengeService;
import dev.livingkingdoms.rpg.RefinementService;
import dev.livingkingdoms.rpg.domain.ChallengeTier;
import dev.livingkingdoms.rpg.domain.RefinementRules;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** {@code /kingdom challenge abandon} for players; start/refine shortcuts are operator development tools. */
public final class RpgCommands {
    private RpgCommands() {}

    private static dev.livingkingdoms.settlement.domain.Settlement here(net.minecraft.server.level.ServerPlayer player) {
        return dev.livingkingdoms.settlement.persistence.SettlementSavedData.get(player.server)
                .alliedAt(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ()).orElse(null);
    }

    private static int event(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                             dev.livingkingdoms.rpg.EventService.Kind kind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException(); var settlement = here(player);
        return settlement != null && dev.livingkingdoms.rpg.EventService.trigger(player.serverLevel(), settlement, kind) ? 1 : 0;
    }

    public static void register(RegisterCommandsEvent event) {
        var start = Commands.literal("start").requires(source -> source.hasPermission(2));
        for (var tier : ChallengeTier.values())
            start.then(Commands.literal(tier.id()).executes(context ->
                    ChallengeService.start(context.getSource().getPlayerOrException(), tier, true) ? 1 : 0));
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("challenge")
                        .then(Commands.literal("abandon").executes(context -> {
                            ChallengeService.abandon(context.getSource().getPlayerOrException()); return 1; }))
                        .then(start))
                .then(Commands.literal("refine").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, RefinementRules.MAX_LEVEL)).executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            var held = player.getMainHandItem();
                            if (!RefinementService.refinable(held)) {
                                context.getSource().sendFailure(Component.translatable("item.livingkingdoms.royal_whetstone.no_weapon")); return 0;
                            }
                            RefinementService.apply(held, IntegerArgumentType.getInteger(context, "level"));
                            context.getSource().sendSuccess(() -> Component.translatable("command.livingkingdoms.refine.done",
                                    RefinementService.level(held)), false);
                            return 1;
                        })))
                .then(Commands.literal("expedition").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("finish").executes(context -> {
                            var server = context.getSource().getServer(); int done = 0;
                            for (var expedition : dev.livingkingdoms.rpg.persistence.RpgSavedData.get(server).expeditions())
                                if (dev.livingkingdoms.rpg.ExpeditionService.finish(server, expedition)) done++;
                            int finished = done;
                            context.getSource().sendSuccess(() -> Component.translatable("command.livingkingdoms.expedition.finished", finished), false);
                            return finished;
                        })))
                .then(Commands.literal("event").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("festival").executes(context -> event(context, dev.livingkingdoms.rpg.EventService.Kind.FESTIVAL)))
                        .then(Commands.literal("caravan").executes(context -> event(context, dev.livingkingdoms.rpg.EventService.Kind.CARAVAN)))
                        .then(Commands.literal("treasure").executes(context -> event(context, dev.livingkingdoms.rpg.EventService.Kind.TREASURE))))
                .then(Commands.literal("beautify").requires(source -> source.hasPermission(2)).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var settlement = here(player);
                    if (settlement == null) return 0;
                    int rank = dev.livingkingdoms.rpg.KingdomProgressService.level(player.server, settlement.id());
                    var applied = dev.livingkingdoms.rpg.TownDecorService.beautify(player.serverLevel(), settlement.id(), rank);
                    context.getSource().sendSuccess(() -> Component.translatable("command.livingkingdoms.beautify.done", applied.size(), rank), false);
                    return applied.size();
                }))
                .then(Commands.literal("perf").requires(source -> source.hasPermission(2)).executes(context -> {
                    for (var line : dev.livingkingdoms.rpg.RpgPerf.report()) context.getSource().sendSuccess(() -> Component.literal(line), false);
                    dev.livingkingdoms.rpg.RpgPerf.reset();
                    return 1;
                }))
                .then(Commands.literal("ledgers").requires(source -> source.hasPermission(2)).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var settlement = here(player);
                    if (settlement == null) return 0;
                    int placed = dev.livingkingdoms.economy.BuildingHearts.ensure(player.serverLevel(), settlement);
                    context.getSource().sendSuccess(() -> Component.translatable("command.livingkingdoms.ledgers.done", placed), false);
                    return placed;
                }))
                .then(Commands.literal("lodge").requires(source -> source.hasPermission(2)).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var settlement = here(player);
                    return settlement != null && dev.livingkingdoms.economy.FoundersLodge.convert(player.serverLevel(), settlement) ? 1 : 0;
                }))
                .then(Commands.literal("map").executes(context -> dev.livingkingdoms.ui.KingdomMapService.open(context.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("wall").requires(source -> source.hasPermission(2)).executes(context -> {
                    // Debug: raise the next wall tier for free and finish it at once.
                    var player = context.getSource().getPlayerOrException();
                    var settlement = here(player);
                    if (settlement == null) return 0;
                    var data = dev.livingkingdoms.rpg.persistence.RpgSavedData.get(player.server);
                    var current = data.wall(settlement.id()).orElse(dev.livingkingdoms.rpg.domain.Fortification.none(dev.livingkingdoms.rpg.WallService.radiusFor(settlement)));
                    if (current.complete() && current.tier() < dev.livingkingdoms.rpg.domain.Fortification.MAX_TIER)
                        data.putWall(settlement.id(), new dev.livingkingdoms.rpg.domain.Fortification(current.tier(), dev.livingkingdoms.rpg.WallService.radiusFor(settlement), 0, true).upgrade());
                    int columns = dev.livingkingdoms.rpg.WallService.buildAll(player.serverLevel(), settlement.id());
                    int tier = dev.livingkingdoms.rpg.WallService.tier(player.server, settlement.id());
                    context.getSource().sendSuccess(() -> Component.translatable("command.livingkingdoms.wall.done", tier, columns), false);
                    return columns;
                }))
                .then(Commands.literal("alarm").requires(source -> source.hasPermission(2)).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var settlement = here(player);
                    if (settlement == null) return 0;
                    dev.livingkingdoms.rpg.AlarmService.sound(player.serverLevel(), settlement);
                    return 1;
                }))
                .then(Commands.literal("whetstones").requires(source -> source.hasPermission(2)).executes(context -> {
                    context.getSource().getPlayerOrException().getInventory().add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 8));
                    return 1;
                })));
    }
}

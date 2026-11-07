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
                .then(Commands.literal("whetstones").requires(source -> source.hasPermission(2)).executes(context -> {
                    context.getSource().getPlayerOrException().getInventory().add(new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 8));
                    return 1;
                })));
    }
}

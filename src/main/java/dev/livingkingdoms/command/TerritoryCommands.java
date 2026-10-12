package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.ui.TerritoryOverlayService;
import dev.livingkingdoms.ui.VillageNames;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Player-local visualization; ordinary players can inspect metadata without changing a world. */
public final class TerritoryCommands {
    private TerritoryCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("chunks")
                .executes(TerritoryCommands::show)
                .then(Commands.literal("off").executes(TerritoryCommands::off))));
    }
    private static int show(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source=context.getSource(); var player=source.getPlayerOrException();
        var settlement=TerritoryOverlayService.resolve(player);
        if(settlement.isEmpty()) {
            TerritoryOverlayService.clear(player);
            source.sendFailure(Component.translatable("commands.livingkingdoms.chunks.not_found",TerritoryOverlayService.NEAREST_DISTANCE));
            return 0;
        }
        var found=settlement.orElseThrow(); var t=found.territory();
        TerritoryOverlayService.show(player,found);
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.chunks.shown",VillageNames.display(found),t.x(),t.y(),t.z(),t.radius(),TerritoryOverlayService.DURATION_TICKS/20),false);
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.chunks.legend"),false);
        return Command.SINGLE_SUCCESS;
    }
    private static int off(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        TerritoryOverlayService.clear(context.getSource().getPlayerOrException());
        context.getSource().sendSuccess(() -> Component.translatable("commands.livingkingdoms.chunks.off"),false);
        return Command.SINGLE_SUCCESS;
    }
}

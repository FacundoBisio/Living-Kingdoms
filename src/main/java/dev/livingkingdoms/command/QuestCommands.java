package dev.livingkingdoms.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Normal player actions behind the board's clickable chat; the server revalidates reach and ownership. */
public final class QuestCommands {
    private QuestCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var quests = Commands.literal("quest");
        for (String action : new String[]{"accept", "claim", "inspect"}) {
            quests.then(Commands.literal(action).then(Commands.argument("id", UuidArgument.uuid())
                    .then(Commands.argument("board", BlockPosArgument.blockPos()).executes(context -> act(context, action)))));
        }
        event.getDispatcher().register(Commands.literal("kingdom").then(quests));
    }

    private static int act(CommandContext<CommandSourceStack> context, String action) throws CommandSyntaxException {
        return ExpandedQuestService.act(context.getSource().getPlayerOrException(),
                BlockPosArgument.getLoadedBlockPos(context, "board"), UuidArgument.getUuid(context, "id"), action) ? 1 : 0;
    }
}

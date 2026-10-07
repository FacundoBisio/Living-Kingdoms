package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.progression.EntityProgression;
import dev.livingkingdoms.progression.RegionalDifficultyService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Read-only operator feedback; no client-controlled level assignment or reroll command. */
public final class ProgressionCommands {
    private ProgressionCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("progression")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info").executes(ProgressionCommands::region)
                        .then(Commands.argument("target", EntityArgument.entity()).executes(ProgressionCommands::entity)))));
    }
    private static int region(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var difficulty = RegionalDifficultyService.at(source.getLevel(), net.minecraft.core.BlockPos.containing(source.getPosition()));
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.progression.region", difficulty.level().value(),
                difficulty.distanceBonus(), difficulty.ageBonus(), difficulty.activityBonus(), difficulty.hostileTierBonus()), false);
        return Command.SINGLE_SUCCESS;
    }
    private static int entity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var entity = EntityArgument.getEntity(context, "target");
        if (!(entity instanceof Mob mob) || dev.livingkingdoms.encounter.EncounterMember.read(mob).isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.progression.unmanaged")); return 0;
        }
        var profile = EntityProgression.read(mob);
        if (profile.isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.progression.unmanaged")); return 0;
        }
        var value = profile.get();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.progression.entity", entity.getUUID().toString(),
                value.level().value(), value.elite(), value.stats().healthBonus(), value.stats().damageBonus(), value.stats().armorBonus(), value.stats().speedBonus()), false);
        return Command.SINGLE_SUCCESS;
    }
}

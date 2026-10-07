package dev.livingkingdoms.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.livingkingdoms.encounter.EncounterSpawner;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator-only encounter controls; reward tests are an explicit administrative action. */
public final class EncounterCommands {
    private EncounterCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var spawn = Commands.literal("spawn");
        for (PartyType type : PartyType.values()) {
            spawn.then(Commands.literal(type.id()).executes(context -> spawn(context, type, false))
                    .then(Commands.literal("reward_test").executes(context -> spawn(context, type, true))));
        }
        event.getDispatcher().register(Commands.literal("kingdom")
                .then(Commands.literal("encounter").requires(source -> source.hasPermission(2))
                        .then(spawn)
                        .then(Commands.literal("info").then(Commands.argument("id", UuidArgument.uuid()).executes(EncounterCommands::info)))));
    }

    private static int spawn(CommandContext<CommandSourceStack> context, PartyType type, boolean rewardTest) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        var result = EncounterSpawner.spawnNear(player.serverLevel(), player.blockPosition(), type, rewardTest);
        if (!result.successful()) {
            String key = switch (result.failure()) {
                case NO_SAFE_SITE -> "commands.livingkingdoms.encounter.no_safe_site";
                case SPAWN_FAILED -> "commands.livingkingdoms.encounter.spawn_failed";
                case PEACEFUL -> "commands.livingkingdoms.encounter.peaceful";
            };
            source.sendFailure(Component.translatable(key));
            return 0;
        }
        HostileParty party = result.party();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.encounter.spawned",
                Component.translatable("encounter.livingkingdoms.type." + type.id()), party.id().toString(),
                party.memberIds().size(), party.origin().x(), party.origin().y(), party.origin().z(),
                Component.translatable(rewardTest ? "encounter.livingkingdoms.mode.reward_test" : "encounter.livingkingdoms.mode.debug")), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var found = EncounterSavedData.get(source.getServer()).get(UuidArgument.getUuid(context, "id"));
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.livingkingdoms.encounter.not_found"));
            return 0;
        }
        HostileParty party = found.orElseThrow();
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.encounter.info", party.id().toString(),
                Component.translatable("faction.livingkingdoms." + party.faction().id()),
                Component.translatable("encounter.livingkingdoms.type." + party.type().id()), party.state().name(),
                party.remainingMembers().size(), party.memberIds().size(), party.threatRating(), party.reputationReward(),
                party.debug(), party.rewardEligible(), party.associatedSettlementId() == null ? "—" : party.associatedSettlementId().toString()), false);
        source.sendSuccess(() -> Component.translatable("commands.livingkingdoms.encounter.levels", party.levels().minimum(),
                party.levels().maximum(), party.levels().average()), false);
        return Command.SINGLE_SUCCESS;
    }
}

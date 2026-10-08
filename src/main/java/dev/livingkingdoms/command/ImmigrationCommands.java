package dev.livingkingdoms.command;

import dev.livingkingdoms.citizen.ImmigrationService;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Development-only proposals use the same housing and approval state as survival arrivals. */
public final class ImmigrationCommands {
    private ImmigrationCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("immigration")
                .requires(source -> source.hasPermission(2)).then(Commands.literal("candidate").executes(context -> {
                    var source=context.getSource(); var player=source.getPlayerOrException(); var pos=player.blockPosition();
                    var settlement=SettlementSavedData.get(source.getServer()).at(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ());
                    var candidate=settlement.flatMap(s -> ImmigrationService.attempt(player.serverLevel(),s,true));
                    boolean generated=candidate.isPresent();
                    Component response=generated ? Component.translatable("citizen.livingkingdoms.debug_created",candidate.orElseThrow().name())
                            : Component.translatable("citizen.livingkingdoms.debug_blocked");
                    if(generated) source.sendSuccess(() -> response,false); else source.sendFailure(response);
                    return generated?1:0;
                }))));
    }
}

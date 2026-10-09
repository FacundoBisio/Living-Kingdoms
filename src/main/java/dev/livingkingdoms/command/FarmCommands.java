package dev.livingkingdoms.command;

import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Read-only operator diagnostics; assignment and construction use normal Mayor UI. */
public final class FarmCommands {
    private FarmCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var root=Commands.literal("kingdom");
        root.then(Commands.literal("settlement").then(Commands.literal("food").requires(s -> s.hasPermission(2))
                .executes(c -> inspect(c.getSource(),false,false))));
        root.then(Commands.literal("farm").then(Commands.literal("debug").requires(s -> s.hasPermission(2))
                .executes(c -> inspect(c.getSource(),true,false))));
        root.then(Commands.literal("citizen").then(Commands.literal("info").requires(s -> s.hasPermission(2))
                .executes(c -> inspect(c.getSource(),true,true))));
        event.getDispatcher().register(root);
    }
    private static int inspect(CommandSourceStack source,boolean workers,boolean citizens) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayerOrException(); var pos=player.blockPosition();
        var s=SettlementSavedData.get(player.server).at(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        if(s==null || !s.faction().isAllied()) { source.sendFailure(Component.translatable("profession.livingkingdoms.debug_outside")); return 0; }
        ProfessionService.ensure(player.serverLevel(),s); var data=ProfessionSavedData.get(player.server);
        var food=ProfessionService.food(player.server,s.id());
        source.sendSuccess(() -> Component.translatable("profession.livingkingdoms.debug_food",s.name(),food.stock(),food.capacity(),food.produced()),false);
        if(workers) for(var b:data.buildings(s.id())) if(b.kind()==dev.livingkingdoms.structure.BuildingKind.FARM)
            source.sendSuccess(() -> Component.translatable("profession.livingkingdoms.debug_farm",b.origin().getX(),b.origin().getZ(),data.workers(b.id()),b.workplaceSlots()),false);
        if(workers) for(var p:data.professions(s.id())) if(citizens || p.type()==dev.livingkingdoms.profession.domain.ProfessionType.FARMER) {
            var person=CitizenSavedData.get(player.server).citizen(p.citizenId()).orElse(null); if(person==null) continue;
            source.sendSuccess(() -> Component.translatable("profession.livingkingdoms.debug_worker",person.name(),person.level().value(),
                    Component.translatable("profession.livingkingdoms."+p.type().name().toLowerCase(java.util.Locale.ROOT)),p.level().value(),p.experience(),
                    Component.translatable("work.livingkingdoms."+p.workState().name().toLowerCase(java.util.Locale.ROOT))),false);
        }
        return 1;
    }
}

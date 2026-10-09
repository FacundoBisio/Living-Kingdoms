package dev.livingkingdoms.command;

import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Read-only operator tools; normal assignment and construction remain in the Mayor UI. */
public final class GuardCommands {
    private GuardCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var root=Commands.literal("kingdom");
        root.then(Commands.literal("guard").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(c -> inspect(c.getSource(),true)))
                .then(Commands.literal("debug").executes(c -> inspect(c.getSource(),true))));
        root.then(Commands.literal("settlement").then(Commands.literal("security").requires(s -> s.hasPermission(2)).executes(c -> inspect(c.getSource(),false))));
        event.getDispatcher().register(root);
    }
    private static int inspect(CommandSourceStack source,boolean guards) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayerOrException(); var pos=player.blockPosition();
        var s=SettlementSavedData.get(player.server).at(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        if(s==null || !s.faction().isAllied()) { source.sendFailure(Component.translatable("profession.livingkingdoms.debug_outside")); return 0; }
        ProfessionService.ensure(player.serverLevel(),s); var data=ProfessionSavedData.get(player.server);
        source.sendSuccess(() -> Component.translatable("guard.livingkingdoms.debug_security",s.name(),SecurityService.score(player.server,s.id())),false);
        if(guards) {
            for(var b:data.buildings(s.id())) if(b.kind()==dev.livingkingdoms.structure.BuildingKind.BARRACKS)
                source.sendSuccess(() -> Component.translatable("guard.livingkingdoms.debug_barracks",b.origin().getX(),b.origin().getZ(),data.workers(b.id()),b.workplaceSlots()),false);
            for(var p:data.professions(s.id())) if(p.type()==ProfessionType.GUARD) {
                var c=CitizenSavedData.get(player.server).citizen(p.citizenId()).orElse(null); if(c==null) continue;
                source.sendSuccess(() -> Component.translatable("profession.livingkingdoms.debug_worker",c.name(),c.level().value(),Component.translatable("profession.livingkingdoms.guard"),
                        p.level().value(),p.experience(),Component.translatable("work.livingkingdoms."+p.workState().name().toLowerCase(java.util.Locale.ROOT))),false);
            }
        }
        return 1;
    }
}

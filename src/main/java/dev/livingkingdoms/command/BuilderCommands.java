package dev.livingkingdoms.command;

import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.Locale;

/** Read-only development inspection; survival players assign workers through the Mayor. */
public final class BuilderCommands {
    private BuilderCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("builder").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(c -> inspect(c.getSource())))));
    }
    private static int inspect(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayerOrException(); var pos=player.blockPosition();
        var s=SettlementSavedData.get(player.server).at(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        if(s==null || !s.faction().isAllied()) { source.sendFailure(Component.translatable("profession.livingkingdoms.debug_outside")); return 0; }
        ProfessionService.ensure(player.serverLevel(),s); var data=ProfessionSavedData.get(player.server);
        long active=data.professions(s.id()).stream().filter(p -> p.active() && p.type()==ProfessionType.BUILDER).count();
        source.sendSuccess(() -> Component.translatable("builder.livingkingdoms.debug_summary",s.name(),active),false);
        for(var b:data.buildings(s.id())) if(b.supports(ProfessionType.BUILDER))
            source.sendSuccess(() -> Component.translatable("builder.livingkingdoms.debug_workplace",b.origin().getX(),b.origin().getZ(),data.workers(b.id()),b.workplaceSlots()),false);
        for(var p:data.professions(s.id())) if(p.type()==ProfessionType.BUILDER) {
            var c=CitizenSavedData.get(player.server).citizen(p.citizenId()).orElse(null); if(c==null) continue;
            source.sendSuccess(() -> Component.translatable("profession.livingkingdoms.debug_worker",c.name(),c.level().value(),Component.translatable("profession.livingkingdoms.builder"),
                    p.level().value(),p.experience(),Component.translatable("work.livingkingdoms."+p.workState().name().toLowerCase(Locale.ROOT))),false);
            ConstructionService.projectForBuilder(player.server,c.id()).ifPresent(entry -> {
                var project=entry.project(); int progress=(int)(project.workTicks()*100/project.durationTicks());
                source.sendSuccess(() -> Component.translatable("builder.livingkingdoms.debug_project",c.name(),
                        Component.translatable("construction.livingkingdoms.building."+project.building().name().toLowerCase(Locale.ROOT)),progress,Math.max(0,project.visualStage())),false);
            });
        }
        return 1;
    }
}

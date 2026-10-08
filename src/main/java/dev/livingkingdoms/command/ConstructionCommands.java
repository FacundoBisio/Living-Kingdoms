package dev.livingkingdoms.command;

import dev.livingkingdoms.construction.*;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

public final class ConstructionCommands {
    private ConstructionCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("construction").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(c -> execute(c,"info")))
                .then(Commands.literal("complete").executes(c -> execute(c,"complete")))
                .then(Commands.literal("advance").executes(c -> execute(c,"advance")))));
    }
    private static int execute(CommandContext<CommandSourceStack> context,String action) throws CommandSyntaxException {
        var source=context.getSource(); var player=source.getPlayerOrException(); var pos=player.blockPosition();
        var settlement=SettlementSavedData.get(source.getServer()).at(player.level().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        if(settlement==null) { source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.not_found")); return 0; }
        var storage=ConstructionSavedData.get(source.getServer()); var entry=ConstructionService.current(source.getServer(),settlement.id()).orElse(null);
        if(action.equals("info")) {
            String details=settlement.lifecycle()+"; "+(entry==null?"no active project":entry.project().building()+" "+entry.project().state()+" "+entry.project().plot()
                    +" supplied="+entry.project().supplied()+" required="+entry.project().required()+" progress="+(int)(entry.project().progress(ConstructionService.now(source.getServer()))*100)+"%");
            source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",details),false); return 1;
        }
        if(entry==null) {
            boolean planned=ConstructionService.ensureNext(player.serverLevel(),settlement.id(),player);
            source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",planned?"Next project planned / founding complete":"No safe next plot"),false); return planned?1:0;
        }
        var p=entry.project();
        if(action.equals("advance") && p.state()==ConstructionState.WAITING_FOR_RESOURCES) {
            java.util.Map<dev.livingkingdoms.quest.expansion.domain.ResourceKind,Integer> materials=new java.util.EnumMap<>(dev.livingkingdoms.quest.expansion.domain.ResourceKind.class);
            p.required().keySet().forEach(k -> { if(p.missing(k)>0) materials.put(k,p.missing(k)); });
            storage.replace(p,p.supply(materials).start(ConstructionService.now(source.getServer())));
        } else if(p.state()==ConstructionState.FAILED) storage.replace(p,p.retry());
        boolean completed=ConstructionService.resolve(source.getServer(),p.id(),player,true);
        source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",completed?"Building completed; advance may provide free test materials":"Supply materials first or clear/load the saved plot; nothing was force-loaded"),false);
        return completed?1:0;
    }
}

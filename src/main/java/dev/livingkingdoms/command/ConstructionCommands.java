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
import com.mojang.brigadier.arguments.IntegerArgumentType;

public final class ConstructionCommands {
    private ConstructionCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kingdom").then(Commands.literal("construction").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(c -> execute(c,"info")))
                .then(Commands.literal("complete").executes(c -> execute(c,"complete")))
                .then(Commands.literal("stage").executes(c -> execute(c,"stage")))
                .then(Commands.literal("advance").executes(c -> execute(c,"advance"))
                        .then(Commands.argument("stage",IntegerArgumentType.integer(0,4)).executes(c -> advance(c,IntegerArgumentType.getInteger(c,"stage")))))));
    }
    private static int advance(CommandContext<CommandSourceStack> context,int stage) throws CommandSyntaxException {
        var source=context.getSource(); var player=source.getPlayerOrException(); var pos=player.blockPosition();
        var settlement=SettlementSavedData.get(source.getServer()).at(player.level().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        var entry=settlement==null?null:ConstructionService.current(source.getServer(),settlement.id()).orElse(null);
        boolean changed=entry!=null && ConstructionService.debugAdvance(source.getServer(),entry.project().id(),stage,player);
        source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",changed?"Stage "+stage+" placed (operator test; free materials, no Builder XP)":"Clear/load the saved plot, or choose a later stage; nothing was force-loaded"),false);
        return changed?1:0;
    }
    private static int execute(CommandContext<CommandSourceStack> context,String action) throws CommandSyntaxException {
        var source=context.getSource(); var player=source.getPlayerOrException(); var pos=player.blockPosition();
        var settlement=SettlementSavedData.get(source.getServer()).at(player.level().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
        if(settlement==null) { source.sendFailure(Component.translatable("commands.livingkingdoms.settlement.not_found")); return 0; }
        var storage=ConstructionSavedData.get(source.getServer()); var entry=ConstructionService.current(source.getServer(),settlement.id()).orElse(null);
        if(action.equals("info") || action.equals("stage")) {
            String details=settlement.lifecycle()+"; "+(entry==null?"no active project":entry.project().building()+" "+entry.project().state()+" "+entry.project().plot()
                    +" supplied="+entry.project().supplied()+" required="+entry.project().required()+" progress="+(int)(entry.project().progress(ConstructionService.now(source.getServer()))*100)+"% stage="+entry.project().visualStage()+" queue="+storage.projects(settlement.id()).stream().filter(e -> e.project().state()!=ConstructionState.COMPLETED).count());
            source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",details),false); return 1;
        }
        if(entry==null) {
            boolean planned=ConstructionService.ensureNext(player.serverLevel(),settlement.id(),player);
            source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",planned?"Next project planned / founding complete":"No safe next plot"),false); return planned?1:0;
        }
        var p=entry.project();
        if(action.equals("advance")) return advance(context,Math.min(4,p.visualStage()+1));
        if(p.state()==ConstructionState.FAILED) storage.replace(p,p.retry());
        boolean completed=ConstructionService.resolve(source.getServer(),p.id(),player,true);
        source.sendSuccess(() -> Component.translatable("construction.livingkingdoms.debug",completed?"Building completed; advance may provide free test materials":"Supply materials first or clear/load the saved plot; nothing was force-loaded"),false);
        return completed?1:0;
    }
}

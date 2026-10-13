package dev.livingkingdoms.economy;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.structure.BuildingKind;

/** Functional survival entry points, also reused by the eventual management screen. */
public final class DevelopmentCommands {
    private DevelopmentCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var root=Commands.literal("development");
        root.then(Commands.literal("plan").then(Commands.argument("building",StringArgumentType.word())
                .suggests((context,builder)-> {for(var kind:new BuildingKind[]{BuildingKind.WAREHOUSE,BuildingKind.LUMBERJACK_HUT,BuildingKind.MINE,BuildingKind.BLACKSMITH,BuildingKind.MARKET}) builder.suggest(kind.name().toLowerCase(java.util.Locale.ROOT));return builder.buildFuture();})
                .executes(context-> {
                    var player=context.getSource().getPlayerOrException();var s=local(player);if(s==null) return result(player,false);
                    BuildingKind kind;try {kind=BuildingKind.valueOf(StringArgumentType.getString(context,"building").toUpperCase(java.util.Locale.ROOT));}catch(IllegalArgumentException invalid) {return result(player,false);}
                    return result(player,ConstructionService.planEconomicBuilding(player.serverLevel(),s.id(),player,kind));
                })));
        root.then(Commands.literal("fund").executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);if(s==null) return result(player,false);
            var project=ConstructionSavedData.get(player.server).projects(s.id()).stream().filter(e->e.project().state()==ConstructionState.WAITING_FOR_RESOURCES).findFirst();
            return result(player,project.isPresent() && ConstructionService.depositStock(player,s.id(),project.get().project().id()));
        }));
        root.then(Commands.literal("upgrade").then(Commands.argument("building",UuidArgument.uuid()).executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);
            return result(player,s!=null && BuildingDevelopment.upgrade(player,s.id(),UuidArgument.getUuid(context,"building")));
        })));
        root.then(Commands.literal("expand").executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);return result(player,s!=null && BuildingDevelopment.expand(player,s.id()));
        }));
        event.getDispatcher().register(Commands.literal("kingdom").then(root));
    }
    private static Settlement local(ServerPlayer player) {
        var pos=player.blockPosition();return SettlementSavedData.get(player.server).alliedAt(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ())
                .filter(s->KingdomEconomy.authorized(player,s.id())).orElse(null);
    }
    private static int result(ServerPlayer player,boolean success) {
        player.displayClientMessage(Component.translatable("economy.livingkingdoms.development."+(success?"success":"refused")),false);return success?1:0;
    }
}

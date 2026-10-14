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
        root.executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);if(s==null) return result(player,false);
            dev.livingkingdoms.profession.ProfessionService.ensure(player.serverLevel(),s);
            var jobs=dev.livingkingdoms.profession.persistence.ProfessionSavedData.get(player.server);
            for(var building:jobs.buildings(s.id())) player.displayClientMessage(Component.translatable("economy.livingkingdoms.building_id",
                    Component.translatable("construction.livingkingdoms.building."+building.kind().name().toLowerCase(java.util.Locale.ROOT)),copy(building.id())),false);
            for(var job:jobs.professions(s.id())) if(job.active() && job.type().operational())
                dev.livingkingdoms.citizen.persistence.CitizenSavedData.get(player.server).citizen(job.citizenId()).ifPresent(c->player.displayClientMessage(Component.translatable("economy.livingkingdoms.citizen_id",c.name(),copy(c.id())),false));
            return 1;
        });
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
        root.then(Commands.literal("repair").then(Commands.argument("building",UuidArgument.uuid()).executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);
            return result(player,s!=null && BuildingDevelopment.repair(player,s.id(),UuidArgument.getUuid(context,"building")));
        })));
        root.then(Commands.literal("repair_equipment").executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);
            return result(player,s!=null && dev.livingkingdoms.profession.EconomicWork.repairHeld(player,s.id()));
        }));
        root.then(Commands.literal("equip_guard").then(Commands.argument("citizen",UuidArgument.uuid()).executes(context-> {
            var player=context.getSource().getPlayerOrException();var s=local(player);
            return result(player,s!=null && dev.livingkingdoms.profession.GuardEquipment.forge(player,s.id(),UuidArgument.getUuid(context,"citizen")));
        })));
        event.getDispatcher().register(Commands.literal("kingdom").then(root));
    }
    private static Settlement local(ServerPlayer player) {
        var pos=player.blockPosition();return SettlementSavedData.get(player.server).alliedAt(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ())
                .filter(s->KingdomEconomy.authorized(player,s.id())).orElse(null);
    }
    private static Component copy(java.util.UUID id) {
        return Component.literal(id.toString()).withStyle(style->style.withColor(net.minecraft.ChatFormatting.AQUA)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD,id.toString())));
    }
    private static int result(ServerPlayer player,boolean success) {
        player.displayClientMessage(Component.translatable("economy.livingkingdoms.development."+(success?"success":"refused")),false);return success?1:0;
    }
}

package dev.livingkingdoms.economy;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;

/** Survival access to the same server-owned transactions used by management UI. */
public final class StockCommands {
    private StockCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var branch=Commands.literal("stock");
        branch.executes(context-> {
            var player=context.getSource().getPlayerOrException();var pos=player.blockPosition();
            var settlement=SettlementSavedData.get(player.server).alliedAt(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ()).orElse(null);
            if(settlement==null || !KingdomEconomy.authorized(player,settlement.id())) return 0;
            var account=KingdomEconomy.ensure(player.server,settlement.id());
            for(var resource:StockResource.values()) player.displayClientMessage(Component.translatable("economy.livingkingdoms.balance",Component.translatable(resource.key()),
                    KingdomEconomy.count(player.server,settlement.id(),resource),resource==StockResource.FOOD?dev.livingkingdoms.profession.ProfessionService.food(player.server,settlement.id()).capacity():account.capacity()),false);
            return 1;
        });
        for(var verb:new String[]{"deposit","withdraw"}) {
            branch.then(Commands.literal(verb).then(Commands.argument("resource",StringArgumentType.word())
                    .suggests((context,builder)-> {for(var resource:StockResource.values()) builder.suggest(resource.name().toLowerCase(java.util.Locale.ROOT));return builder.buildFuture();})
                    .executes(context-> {
                        var player=context.getSource().getPlayerOrException();var p=player.blockPosition();
                        var settlement=SettlementSavedData.get(player.server).alliedAt(player.serverLevel().dimension().location().toString(),p.getX(),p.getZ()).orElse(null);
                        StockResource resource;try {resource=StockResource.valueOf(StringArgumentType.getString(context,"resource").toUpperCase(java.util.Locale.ROOT));}
                        catch(IllegalArgumentException invalid) {return 0;}
                        int count=settlement==null?0:verb.equals("deposit")?KingdomEconomy.deposit(player,settlement.id(),resource,16):KingdomEconomy.withdraw(player,settlement.id(),resource,resource==StockResource.EQUIPMENT?1:16);
                        player.displayClientMessage(resource==StockResource.FOOD && count>0
                                ? Component.translatable("economy.livingkingdoms.food_"+verb,count,resource.unitsForItems(count,dev.livingkingdoms.config.ProfessionConfig.WHEAT_FOOD.get()))
                                : Component.translatable("economy.livingkingdoms."+(count>0?verb:"refused"),count,Component.translatable(resource.key())),false);return count>0?1:0;
                    })));
        }
        event.getDispatcher().register(Commands.literal("kingdom").then(branch));
    }
}

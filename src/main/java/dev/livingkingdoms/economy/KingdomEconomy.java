package dev.livingkingdoms.economy;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.config.EconomyConfig;
import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.FoodStock;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.expansion.domain.ResourceRequirement;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Server-thread exchange boundary. Inventory preflight and persistent stock commit never yield. */
public final class KingdomEconomy {
    public record Transfer(UUID settlement,StockAccount before,StockAccount after,FoodStock foodBefore,int foodSpent,int foodGained,boolean directWithdrawal) {
        public Transfer(UUID settlement,StockAccount before,StockAccount after,FoodStock foodBefore,int foodSpent,int foodGained) {this(settlement,before,after,foodBefore,foodSpent,foodGained,false);}
    }
    private KingdomEconomy() {}

    public static StockAccount ensure(MinecraftServer server,UUID settlement) {
        if(SettlementSavedData.get(server).get(settlement).filter(s->s.faction().isAllied()).isEmpty()) throw new IllegalArgumentException("Unknown allied economy");
        var food=ProfessionService.food(server,settlement);
        ProfessionSavedData.get(server).ensureFood(settlement,Math.max(food.capacity(),ProfessionConfig.FOOD_CAPACITY.get()));
        return EconomySavedData.get(server).ensure(settlement,capacity(server,settlement),now(server));
    }
    public static int capacity(MinecraftServer server,UUID settlement) {
        return (int)Math.min(1_000_000L,(long)EconomyConfig.BASE_CAPACITY.get()+(long)EconomyConfig.WAREHOUSE_CAPACITY.get()*BuildingDevelopment.warehouseLevels(server,settlement));
    }
    public static long now(MinecraftServer server) { return Math.max(0,server.overworld().getGameTime()); }
    public static int count(MinecraftServer server,UUID settlement,StockResource resource) {
        return resource==StockResource.FOOD?ProfessionService.food(server,settlement).stock():EconomySavedData.get(server).account(settlement).map(s->s.balance(resource)).orElse(0);
    }
    public static int itemCount(MinecraftServer server,UUID settlement,StockResource resource) {
        return resource.itemsFromUnits(count(server,settlement,resource),ProfessionConfig.WHEAT_FOOD.get());
    }
    public static Optional<Transfer> plan(MinecraftServer server,UUID settlement,Map<StockResource,Integer> spent,Map<StockResource,Integer> gained,boolean production) {
        var account=ensure(server,settlement);var food=ProfessionService.food(server,settlement);
        var costs=nonFood(spent);var outputs=nonFood(gained);
        int foodSpent=spent.getOrDefault(StockResource.FOOD,0),foodGained=gained.getOrDefault(StockResource.FOOD,0);
        if(foodSpent<0 || foodGained<0 || foodSpent>food.stock() || (long)food.stock()-foodSpent+foodGained>food.capacity()
                || production && foodGained>0) return Optional.empty();
        return account.exchange(costs,outputs,production).map(next->new Transfer(settlement,account,next,food,foodSpent,foodGained));
    }
    public static boolean valid(MinecraftServer server,Transfer transfer) {
        return EconomySavedData.get(server).account(transfer.settlement()).filter(transfer.before()::equals).isPresent()
                && (!transfer.directWithdrawal() || transfer.foodSpent()<=transfer.foodBefore().stock()-transfer.foodBefore().pledged())
                && ProfessionService.food(server,transfer.settlement()).equals(transfer.foodBefore());
    }
    public static boolean commit(MinecraftServer server,Transfer transfer) {
        if(!valid(server,transfer) || !EconomySavedData.get(server).replace(transfer.settlement(),transfer.before(),transfer.after())) return false;
        if(!ProfessionSavedData.get(server).exchangeFood(transfer.settlement(),transfer.foodBefore(),transfer.foodSpent(),transfer.foodGained(),transfer.directWithdrawal()))
            throw new IllegalStateException("Food changed inside an atomic server-thread exchange");
        return true;
    }
    public static boolean authorized(ServerPlayer player,UUID settlement) {
        if(!player.server.isSameThread()) throw new IllegalStateException("Economic action requires server thread");
        var s=SettlementSavedData.get(player.server).get(settlement).orElse(null);
        return s!=null && s.faction().isAllied() && player.isAlive() && !player.isSpectator() && player.mayBuild()
                && s.territory().contains(player.serverLevel().dimension().location().toString(),player.blockPosition().getX(),player.blockPosition().getZ())
                && KingdomOnboardingService.canHelp(player,s);
    }
    public static int deposit(ServerPlayer player,UUID settlement,StockResource resource,int limit) {
        if(!authorized(player,settlement) || resource.material()==null || limit<1 || limit>64) return 0;
        var account=ensure(player.server,settlement);var food=ProfessionService.food(player.server,settlement);
        int room=resource==StockResource.FOOD?food.capacity()-food.stock():account.room(resource);
        int wheatUnits=ProfessionConfig.WHEAT_FOOD.get();
        int amount=Math.min(Math.min(resource.itemsFromUnits(room,wheatUnits),limit),DeliveryInventory.count(player.getInventory(),resource.material()));
        if(amount<1) return 0;
        var inventory=DeliveryInventory.plan(player.getInventory(),List.of(new ResourceRequirement(resource.material(),amount)),0).orElseThrow();
        var transfer=plan(player.server,settlement,Map.of(),Map.of(resource,resource.unitsForItems(amount,wheatUnits)),false).orElseThrow();
        if(!commit(player.server,transfer)) return 0;
        inventory.apply(player.getInventory());sync(player);return amount;
    }
    public static int withdraw(ServerPlayer player,UUID settlement,StockResource resource,int limit) {
        if(!authorized(player,settlement) || limit<1 || limit>64) return 0;
        ensure(player.server,settlement);int wheatUnits=ProfessionConfig.WHEAT_FOOD.get();
        int available=resource==StockResource.FOOD?ProfessionService.food(player.server,settlement).surplus(ProfessionConfig.HEALTHY_FOOD.get()):count(player.server,settlement,resource);
        int amount=Math.min(limit,resource.itemsFromUnits(available,wheatUnits));
        if(amount<1) return 0;
        var inventory=DeliveryInventory.planReward(player.getInventory(),new ItemStack(resource.item()),amount);
        if(inventory.isEmpty()) return 0;
        var transfer=plan(player.server,settlement,Map.of(resource,resource.unitsForItems(amount,wheatUnits)),Map.of(),false).orElseThrow();
        transfer=new Transfer(transfer.settlement(),transfer.before(),transfer.after(),transfer.foodBefore(),transfer.foodSpent(),transfer.foodGained(),true);
        if(!commit(player.server,transfer)) return 0;
        inventory.orElseThrow().apply(player.getInventory());sync(player);return amount;
    }
    /** Native chest-style shift-click spends only the selected inventory slot, preserving its other stacks. */
    public static int depositSlot(ServerPlayer player,UUID settlement,StockResource resource,int slot,int limit) {
        if(!authorized(player,settlement) || slot<0 || slot>=36 || limit<1 || limit>64 || resource.material()==null) return 0;
        var stack=player.getInventory().getItem(slot);
        boolean matches=switch(resource) {case FOOD->stack.is(net.minecraft.world.item.Items.WHEAT);case WOOD->stack.is(net.minecraft.tags.ItemTags.LOGS);case STONE->stack.is(net.minecraft.world.item.Items.STONE);case IRON->stack.is(net.minecraft.world.item.Items.IRON_INGOT);case EQUIPMENT->false;};
        if(!matches) return 0;
        var account=ensure(player.server,settlement);var food=ProfessionService.food(player.server,settlement);int nutrition=ProfessionConfig.WHEAT_FOOD.get();
        int room=resource==StockResource.FOOD?food.capacity()-food.stock():account.room(resource);
        int amount=Math.min(limit,Math.min(stack.getCount(),resource.itemsFromUnits(room,nutrition)));if(amount<1) return 0;
        var transfer=plan(player.server,settlement,Map.of(),Map.of(resource,resource.unitsForItems(amount,nutrition)),false).orElseThrow();
        if(!commit(player.server,transfer)) return 0;stack.shrink(amount);player.getInventory().setChanged();sync(player);return amount;
    }
    public static boolean consume(MinecraftServer server,Settlement settlement) {
        var account=ensure(server,settlement.id());long now=now(server);
        if(now-account.consumedAt()<EconomyConfig.CONSUMPTION_INTERVAL.get()) return false;
        int population=CitizenService.population(server,settlement);
        int requested=(int)(((long)population+EconomyConfig.CITIZENS_PER_FOOD.get()-1)/EconomyConfig.CITIZENS_PER_FOOD.get());
        var food=ProfessionService.food(server,settlement.id());int consumed=Math.min(requested,food.stock());
        if(!EconomySavedData.get(server).replace(settlement.id(),account,account.consume(now,consumed))) return false;
        if(!ProfessionSavedData.get(server).exchangeFood(settlement.id(),food,consumed,0)) throw new IllegalStateException("Concurrent food consumption");
        return true;
    }
    private static Map<StockResource,Integer> nonFood(Map<StockResource,Integer> values) {
        var result=new EnumMap<StockResource,Integer>(StockResource.class);
        for(var entry:values.entrySet()) {
            if(entry.getValue()<1 || entry.getValue()>1_000_000) throw new IllegalArgumentException("Invalid economic amount");
            if(entry.getKey()!=StockResource.FOOD) result.put(entry.getKey(),entry.getValue());
        }
        return result;
    }
    public static void sync(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();if(player.containerMenu!=player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
}

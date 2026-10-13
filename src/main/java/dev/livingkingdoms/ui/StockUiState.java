package dev.livingkingdoms.ui;

import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.config.ProfessionConfig;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded on-demand inventory/ledger view. Target identities are stable across enum reordering. */
public final class StockUiState {
    private static final Map<UUID,StockResource> TARGETS=new LinkedHashMap<>();
    static {for(var resource:StockResource.values()) TARGETS.put(UUID.nameUUIDFromBytes(("livingkingdoms:stock:"+resource.name()).getBytes(StandardCharsets.UTF_8)),resource);}
    private StockUiState() {}
    public static UUID target(StockResource resource) {return TARGETS.entrySet().stream().filter(e->e.getValue()==resource).findFirst().orElseThrow().getKey();}
    public static Optional<StockResource> resource(UUID target) {return Optional.ofNullable(TARGETS.get(target));}
    public static void write(ServerPlayer player,UUID settlement,CompoundTag tag) {
        var account=KingdomEconomy.ensure(player.server,settlement);var food=ProfessionService.food(player.server,settlement);int nutrition=ProfessionConfig.WHEAT_FOOD.get();var rows=new ListTag();
        for(var r:StockResource.values()) {
            var row=new CompoundTag();row.putUUID("id",target(r));row.putString("resource",r.name());
            int balance=KingdomEconomy.count(player.server,settlement,r),capacity=r==StockResource.FOOD?food.capacity():account.capacity();
            int carried=r.material()==null?0:DeliveryInventory.count(player.getInventory(),r.material());
            int room=r==StockResource.FOOD?food.capacity()-food.stock():account.room(r);
            int deposit=r.material()==null?0:Math.min(16,Math.min(carried,r.itemsFromUnits(room,nutrition)));
            int units=r==StockResource.FOOD?food.surplus(ProfessionConfig.HEALTHY_FOOD.get()):balance;
            int withdrawal=Math.min(r==StockResource.EQUIPMENT?1:16,r.itemsFromUnits(units,nutrition));
            row.putInt("balance",balance);row.putInt("capacity",capacity);row.putInt("carried",carried);row.putInt("deposit",deposit);row.putInt("withdraw",withdrawal);
            row.putBoolean("inventory_room",withdrawal>0 && DeliveryInventory.planReward(player.getInventory(),new ItemStack(r.item()),withdrawal).isPresent());
            if(r==StockResource.FOOD) {row.putInt("reserved",food.reserve(ProfessionConfig.HEALTHY_FOOD.get()));row.putInt("pledged",food.pledged());row.putInt("nutrition",nutrition);}
            rows.add(row);
        }
        tag.put("stocks",rows);
    }
}

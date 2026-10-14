package dev.livingkingdoms.config;

import dev.livingkingdoms.economy.StockResource;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.EnumMap;

/** Prices are per single canonical item; sale prices stay strictly below purchase prices. */
public final class CommerceConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue SLOTS,RESTOCK_TICKS,STOCK_LIMIT,INITIAL_EMERALDS;
    private static final EnumMap<StockResource,ModConfigSpec.IntValue> BUY=new EnumMap<>(StockResource.class),SELL=new EnumMap<>(StockResource.class);
    static {
        var b=new ModConfigSpec.Builder();b.push("commerce");
        SLOTS=b.defineInRange("merchantSlots",2,1,8);
        RESTOCK_TICKS=b.defineInRange("restockTicks",24000,1200,1728000);
        STOCK_LIMIT=b.comment("Shared per-resource, per-direction transaction quota. Restock never creates kingdom materials or emeralds.").defineInRange("stockLimit",16,1,128);
        INITIAL_EMERALDS=b.comment("One-time treasury when a kingdom first opens commerce; never refilled by restock.").defineInRange("initialEmeralds",32,0,10000);
        for(var r:StockResource.values()) {
            b.push(r.name().toLowerCase(java.util.Locale.ROOT));
            int price=switch(r){case FOOD,WOOD,STONE->2;case IRON->3;case EQUIPMENT->16;};
            BUY.put(r,b.defineInRange("buyPriceEmeralds",price,2,64));
            SELL.put(r,b.defineInRange("sellPriceEmeralds",Math.max(1,price/2),1,64));b.pop();
        }
        b.pop();SPEC=b.build();
    }
    public static int price(StockResource r,boolean buy) {return buy?BUY.get(r).get():Math.min(BUY.get(r).get()-1,SELL.get(r).get());}
    private CommerceConfig() {}
}

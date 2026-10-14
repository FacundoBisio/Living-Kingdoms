package dev.livingkingdoms.commerce;

import dev.livingkingdoms.economy.StockResource;
import java.util.*;

/** Finite kingdom-wide quotas and a conserved emerald treasury; restock creates neither goods nor money. */
public record MarketAccount(int emeralds,long revision,long restockAt,Map<StockResource,Integer> bought,Map<StockResource,Integer> sold) {
    public MarketAccount {
        bought=canonical(bought);sold=canonical(sold);
        if(emeralds<0 || emeralds>1_000_000 || revision<0 || restockAt<0) throw new IllegalArgumentException("Invalid market account");
        for(var counts:List.of(bought,sold)) for(var amount:counts.values()) if(amount<0 || amount>128) throw new IllegalArgumentException("Invalid trade quota");
    }
    private static Map<StockResource,Integer> canonical(Map<StockResource,Integer> values) {
        var result=new EnumMap<StockResource,Integer>(StockResource.class);
        values.forEach((key,value)->{Objects.requireNonNull(key);if(value!=0)result.put(key,value);});return Map.copyOf(result);
    }
    public static MarketAccount empty(int emeralds,long now,int interval) {return new MarketAccount(emeralds,0,Math.addExact(now,interval),Map.of(),Map.of());}
    public MarketAccount restock(long now,int interval) {return now<restockAt?this:new MarketAccount(emeralds,revision+1,Math.addExact(now,interval),Map.of(),Map.of());}
    public int remaining(StockResource resource,boolean buy,int limit) {return Math.max(0,limit-(buy?bought:sold).getOrDefault(resource,0));}
    public Optional<MarketAccount> trade(StockResource resource,boolean buy,int price,int limit) {
        if(resource==null || price<1 || price>64 || limit<1 || limit>128 || remaining(resource,buy,limit)==0) return Optional.empty();
        long money=(long)emeralds+(buy?price:-price);if(money<0 || money>1_000_000) return Optional.empty();
        var counts=new EnumMap<StockResource,Integer>(StockResource.class);counts.putAll(buy?bought:sold);counts.merge(resource,1,Integer::sum);
        return Optional.of(new MarketAccount((int)money,revision+1,restockAt,buy?counts:bought,buy?sold:counts));
    }
}

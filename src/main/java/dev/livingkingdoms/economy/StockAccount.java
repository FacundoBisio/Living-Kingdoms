package dev.livingkingdoms.economy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

/** Immutable compare-and-set account. Decreased capacity never deletes already stored materials. */
public record StockAccount(Map<StockResource,Integer> balances,Map<StockResource,Long> produced,
                           int capacity,long revision,long consumedAt,long consumedFood,
                           Map<UUID,Integer> buildingLevels,Set<UUID> repairOrders) {
    public StockAccount(Map<StockResource,Integer> balances,Map<StockResource,Long> produced,
                        int capacity,long revision,long consumedAt,long consumedFood) {
        this(balances,produced,capacity,revision,consumedAt,consumedFood,Map.of(),Set.of());
    }
    public StockAccount {
        balances=Map.copyOf(balances);produced=Map.copyOf(produced);
        if(capacity<1 || capacity>1_000_000 || revision<0 || revision>1_000_000_000_000L || consumedAt<0 || consumedFood<0)
            throw new IllegalArgumentException("Invalid stock account");
        for(var entry:balances.entrySet()) if(entry.getKey()==StockResource.FOOD || entry.getValue()<0 || entry.getValue()>1_000_000)
            throw new IllegalArgumentException("Invalid balance or duplicated food");
        for(var entry:produced.entrySet()) if(entry.getKey()==StockResource.FOOD || entry.getValue()<0 || entry.getValue()>1_000_000_000_000L)
            throw new IllegalArgumentException("Invalid production counter");
        var positiveBalances=new EnumMap<StockResource,Integer>(StockResource.class);
        var positiveProduction=new EnumMap<StockResource,Long>(StockResource.class);
        balances.forEach((kind,value)-> {if(value>0) positiveBalances.put(kind,value);});
        produced.forEach((kind,value)-> {if(value>0) positiveProduction.put(kind,value);});
        balances=Map.copyOf(positiveBalances);produced=Map.copyOf(positiveProduction);
        buildingLevels=Map.copyOf(buildingLevels);repairOrders=Set.copyOf(repairOrders);
        if(buildingLevels.size()>512 || repairOrders.size()>512 || buildingLevels.values().stream().anyMatch(level->level<2 || level>5))
            throw new IllegalArgumentException("Invalid building development");
    }
    public static StockAccount empty(int capacity,long now) { return new StockAccount(Map.of(),Map.of(),capacity,0,now,0); }
    public int balance(StockResource kind) { return balances.getOrDefault(kind,0); }
    public int room(StockResource kind) { return Math.max(0,capacity-balance(kind)); }
    public StockAccount capacity(int value) { return value==capacity?this:new StockAccount(balances,produced,value,revision+1,consumedAt,consumedFood,buildingLevels,repairOrders); }
    public int buildingLevel(UUID building) { return buildingLevels.getOrDefault(building,1); }
    public StockAccount upgrade(UUID building) {
        var next=new HashMap<>(buildingLevels);next.put(building,buildingLevel(building)+1);
        return new StockAccount(balances,produced,capacity,revision+1,consumedAt,consumedFood,next,repairOrders);
    }
    public StockAccount repair(UUID building,boolean requested) {
        var next=new HashSet<>(repairOrders);if(requested) next.add(building);else next.remove(building);
        return new StockAccount(balances,produced,capacity,revision+1,consumedAt,consumedFood,buildingLevels,next);
    }
    public Optional<StockAccount> exchange(Map<StockResource,Integer> spent,Map<StockResource,Integer> gained,boolean production) {
        var next=new EnumMap<StockResource,Integer>(StockResource.class);next.putAll(balances);
        var totals=new EnumMap<StockResource,Long>(StockResource.class);totals.putAll(produced);
        for(var cost:spent.entrySet()) {
            check(cost);int old=next.getOrDefault(cost.getKey(),0);if(old<cost.getValue()) return Optional.empty();
            next.put(cost.getKey(),old-cost.getValue());
        }
        for(var output:gained.entrySet()) {
            check(output);long value=(long)next.getOrDefault(output.getKey(),0)+output.getValue();
            if(value>capacity) return Optional.empty();next.put(output.getKey(),(int)value);
            if(production) totals.merge(output.getKey(),(long)output.getValue(),Math::addExact);
        }
        return Optional.of(new StockAccount(next,totals,capacity,revision+1,consumedAt,consumedFood,buildingLevels,repairOrders));
    }
    public StockAccount consume(long now,int amount) {
        if(now<consumedAt || amount<0) throw new IllegalArgumentException("Invalid consumption");
        return new StockAccount(balances,produced,capacity,revision+1,now,Math.addExact(consumedFood,amount),buildingLevels,repairOrders);
    }
    private static void check(Map.Entry<StockResource,Integer> term) {
        if(term.getKey()==StockResource.FOOD || term.getValue()<1 || term.getValue()>1_000_000) throw new IllegalArgumentException("Invalid stock exchange");
    }
}

package dev.livingkingdoms.profession.domain;

public record FoodStock(int stock,int capacity,long produced,int pledged) {
    public FoodStock(int stock,int capacity,long produced) {this(stock,capacity,produced,0);}
    public FoodStock { if(capacity<1 || capacity>1_000_000 || stock<0 || stock>capacity || produced<0 || pledged<0 || pledged>stock) throw new IllegalArgumentException("Invalid food stock"); }
    public FoodStock add(int units) {
        if(units<0) throw new IllegalArgumentException("Negative food");
        int accepted=Math.min(units,capacity-stock);
        return new FoodStock(stock+accepted,capacity,Math.addExact(produced,accepted),pledged);
    }
    /** Rewarded mission supplies belong to residents/workers until consumed, not to a refundable deposit. */
    public FoodStock pledge(int units) {
        var added=add(units);return new FoodStock(added.stock,capacity,added.produced,pledged+added.stock-stock);
    }
    public FoodStock capacity(int value) { return new FoodStock(Math.min(stock,value),value,produced,Math.min(pledged,Math.min(stock,value))); }
    public int reserve(int healthyReserve) {
        if(healthyReserve<0) throw new IllegalArgumentException("Negative food reserve");return Math.min(capacity,Math.max(healthyReserve,pledged));
    }
    public int surplus(int healthyReserve) {
        if(healthyReserve<0) throw new IllegalArgumentException("Negative food reserve");
        return Math.max(0,stock-reserve(healthyReserve));
    }
    public FoodStock deposit(int units) {
        if(units<0) throw new IllegalArgumentException("Negative food");
        return new FoodStock(stock+Math.min(units,capacity-stock),capacity,produced,pledged);
    }
    public FoodStock consume(int units) {
        if(units<0 || units>stock) throw new IllegalArgumentException("Insufficient food");
        return new FoodStock(stock-units,capacity,produced,Math.max(0,pledged-units));
    }
    public FoodStock withdraw(int units) {
        if(units<0 || units>stock-pledged) throw new IllegalArgumentException("Food is pledged to the community");
        return new FoodStock(stock-units,capacity,produced,pledged);
    }
}

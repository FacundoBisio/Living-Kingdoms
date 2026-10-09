package dev.livingkingdoms.profession.domain;

public record FoodStock(int stock,int capacity,long produced) {
    public FoodStock { if(capacity<1 || capacity>1_000_000 || stock<0 || stock>capacity || produced<0) throw new IllegalArgumentException("Invalid food stock"); }
    public FoodStock add(int units) {
        if(units<0) throw new IllegalArgumentException("Negative food");
        int accepted=Math.min(units,capacity-stock);
        return new FoodStock(stock+accepted,capacity,Math.addExact(produced,accepted));
    }
    public FoodStock capacity(int value) { return new FoodStock(Math.min(stock,value),value,produced); }
}

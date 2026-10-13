package dev.livingkingdoms.economy;

import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Economic units bridge existing quest materials; food remains in the original FoodStock. */
public enum StockResource {
    FOOD(ResourceKind.WHEAT), WOOD(ResourceKind.LOGS), STONE(ResourceKind.STONE), IRON(ResourceKind.IRON_INGOT), EQUIPMENT(null);
    private final ResourceKind material;
    StockResource(ResourceKind material) { this.material=material; }
    public ResourceKind material() { return material; }
    /** Food balances are nutrition units, matching crop and quest-delivery policy. */
    public int unitsPerItem(int wheatUnits) {
        if(wheatUnits<1 || wheatUnits>100) throw new IllegalArgumentException("Invalid wheat nutrition");
        return this==FOOD?wheatUnits:1;
    }
    public int itemsFromUnits(int units,int wheatUnits) {
        if(units<0) throw new IllegalArgumentException("Negative stock");return units/unitsPerItem(wheatUnits);
    }
    public int unitsForItems(int items,int wheatUnits) {
        if(items<0) throw new IllegalArgumentException("Negative items");return Math.multiplyExact(items,unitsPerItem(wheatUnits));
    }
    public Item item() { return switch(this) {
        case FOOD->Items.WHEAT;case WOOD->Items.OAK_LOG;case STONE->Items.STONE;case IRON->Items.IRON_INGOT;case EQUIPMENT->Items.IRON_CHESTPLATE;
    }; }
    public static StockResource of(ResourceKind material) {
        for(var resource:values()) if(resource.material==material) return resource;
        throw new IllegalArgumentException("Unmapped material");
    }
    public String key() { return "economy.livingkingdoms.resource."+name().toLowerCase(java.util.Locale.ROOT); }
}

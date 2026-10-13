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
    public Item item() { return switch(this) {
        case FOOD->Items.WHEAT;case WOOD->Items.OAK_LOG;case STONE->Items.STONE;case IRON->Items.IRON_INGOT;case EQUIPMENT->Items.IRON_CHESTPLATE;
    }; }
    public static StockResource of(ResourceKind material) {
        for(var resource:values()) if(resource.material==material) return resource;
        throw new IllegalArgumentException("Unmapped material");
    }
    public String key() { return "economy.livingkingdoms.resource."+name().toLowerCase(java.util.Locale.ROOT); }
}

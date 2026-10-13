package dev.livingkingdoms.profession.domain;

import dev.livingkingdoms.economy.StockResource;
import java.util.Map;

/** Bounded abstract extraction avoids destructive mining/tree searches. Every batch consumes real stored inputs. */
public record EconomicRecipe(Map<StockResource,Integer> inputs,Map<StockResource,Integer> outputs) {
    public EconomicRecipe {inputs=Map.copyOf(inputs);outputs=Map.copyOf(outputs);}
    public static EconomicRecipe forProfession(ProfessionType type,int level) {
        int bonus=Math.clamp(level-1,0,4);
        return switch(type) {
            case LUMBERJACK -> new EconomicRecipe(Map.of(StockResource.FOOD,1),Map.of(StockResource.WOOD,4+bonus));
            case MINER -> new EconomicRecipe(Map.of(StockResource.FOOD,2,StockResource.WOOD,1),Map.of(StockResource.STONE,4+bonus,StockResource.IRON,1));
            case BLACKSMITH -> new EconomicRecipe(Map.of(StockResource.FOOD,2,StockResource.WOOD,2,StockResource.IRON,8),Map.of(StockResource.EQUIPMENT,1));
            default -> throw new IllegalArgumentException("No economic recipe for this profession");
        };
    }
    public static int cooldown(int base,int professionLevel,int buildingLevel) {
        return Math.max(200,base-Math.clamp(professionLevel-1,0,4)*80-Math.clamp(buildingLevel-1,0,4)*120);
    }
}

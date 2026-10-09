package dev.livingkingdoms.profession;

import dev.livingkingdoms.config.ProfessionConfig;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit crop adapter boundary: one harvest replants the same crop, with no vanilla loot generation. */
public final class CropPolicy {
    public record Harvest(BlockState replant,int food) {}
    private CropPolicy() {}
    public static Optional<Harvest> mature(BlockState state) {
        if(!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return Optional.empty();
        int units;
        if(state.is(Blocks.WHEAT)) units=ProfessionConfig.WHEAT_FOOD.get();
        else if(state.is(Blocks.CARROTS)) units=ProfessionConfig.CARROT_FOOD.get();
        else if(state.is(Blocks.POTATOES)) units=ProfessionConfig.POTATO_FOOD.get();
        else return Optional.empty();
        return Optional.of(new Harvest(crop.getStateForAge(0),dev.livingkingdoms.profession.domain.FarmerProgression.food(units,ProfessionConfig.FOOD_PORTION.get())));
    }
}

package dev.livingkingdoms.commerce;

import dev.livingkingdoms.quest.DeliveryInventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import java.util.*;

/** Trades only pristine canonical stacks. Custom components/damaged gear cannot be laundered into stock. */
public final class CommerceInventory {
    private CommerceInventory() {}
    public static Optional<DeliveryInventory.Exchange> plan(Inventory inventory,Item input,int inputCount,Item output,int outputCount) {
        if(inputCount<1 || inputCount>64 || outputCount<1 || outputCount>64)throw new IllegalArgumentException("Invalid trade amount");
        var items=copies(inventory.items);var offhand=copies(inventory.offhand);var wanted=new ItemStack(input);int remaining=inputCount;
        for(var list:List.of(items,offhand))for(int i=0;i<list.size() && remaining>0;i++) {
            var stack=list.get(i);if(!ItemStack.isSameItemSameComponents(stack,wanted))continue;
            int used=Math.min(remaining,stack.getCount());stack.shrink(used);remaining-=used;if(stack.isEmpty())list.set(i,ItemStack.EMPTY);
        }
        if(remaining!=0)return Optional.empty();
        var reward=new ItemStack(output);remaining=outputCount;
        for(var stack:items)if(!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack,reward)) {
            int added=Math.min(remaining,Math.max(0,Math.min(stack.getMaxStackSize(),inventory.getMaxStackSize())-stack.getCount()));stack.grow(added);remaining-=added;
        }
        for(int i=0;i<items.size() && remaining>0;i++)if(items.get(i).isEmpty()) {
            int added=Math.min(remaining,Math.min(reward.getMaxStackSize(),inventory.getMaxStackSize()));items.set(i,reward.copyWithCount(added));remaining-=added;
        }
        return remaining==0?Optional.of(new DeliveryInventory.Exchange(items,offhand)):Optional.empty();
    }
    private static List<ItemStack> copies(List<ItemStack> values){var result=new ArrayList<ItemStack>(values.size());values.forEach(s->result.add(s.copy()));return result;}
}

package dev.livingkingdoms.quest;

import dev.livingkingdoms.quest.domain.QuestTerms;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Plans the entire exchange on copies; neither refusal nor a full inventory consumes items. */
final class DeliveryInventory {
    private DeliveryInventory() {}

    static int ironCount(Inventory inventory) {
        return count(inventory.items) + count(inventory.offhand);
    }

    private static int count(List<ItemStack> stacks) {
        return stacks.stream().filter(stack -> stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
    }

    static Optional<Exchange> plan(Inventory inventory, QuestTerms terms) {
        List<ItemStack> items = copies(inventory.items);
        List<ItemStack> offhand = copies(inventory.offhand);
        int remaining = consume(items, terms.requiredIron());
        remaining = consume(offhand, remaining);
        if (remaining != 0) return Optional.empty();

        ItemStack emerald = new ItemStack(Items.EMERALD);
        remaining = terms.rewardEmeralds();
        // Merge only identical components, then use empty main-inventory slots.
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, emerald)) {
                int added = Math.min(remaining, Math.max(0, Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()) - stack.getCount()));
                stack.grow(added);
                remaining -= added;
            }
        }
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            if (items.get(i).isEmpty()) {
                int added = Math.min(remaining, Math.min(emerald.getMaxStackSize(), inventory.getMaxStackSize()));
                items.set(i, new ItemStack(Items.EMERALD, added));
                remaining -= added;
            }
        }
        return remaining == 0 ? Optional.of(new Exchange(items, offhand)) : Optional.empty();
    }

    private static List<ItemStack> copies(List<ItemStack> source) {
        List<ItemStack> copy = new ArrayList<>(source.size());
        source.forEach(stack -> copy.add(stack.copy()));
        return copy;
    }

    private static int consume(List<ItemStack> stacks, int remaining) {
        for (int i = 0; i < stacks.size() && remaining > 0; i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.is(Items.IRON_INGOT)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) stacks.set(i, ItemStack.EMPTY);
        }
        return remaining;
    }

    record Exchange(List<ItemStack> items, List<ItemStack> offhand) {
        void apply(Inventory inventory) {
            for (int i = 0; i < items.size(); i++) inventory.items.set(i, items.get(i));
            for (int i = 0; i < offhand.size(); i++) inventory.offhand.set(i, offhand.get(i));
            inventory.setChanged();
        }
    }
}

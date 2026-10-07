package dev.livingkingdoms.quest;

import dev.livingkingdoms.quest.domain.QuestTerms;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.quest.expansion.domain.ResourceRequirement;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Plans the entire exchange on copies; neither refusal nor a full inventory consumes items. */
public final class DeliveryInventory {
    private DeliveryInventory() {}

    public static int ironCount(Inventory inventory) {
        return count(inventory, ResourceKind.IRON_INGOT);
    }

    /** Counts carried resources; armor slots never count as quest deliveries. */
    public static int count(Inventory inventory, ResourceKind resource) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(resource, "resource");
        return count(inventory.items, resource) + count(inventory.offhand, resource);
    }

    private static int count(List<ItemStack> stacks, ResourceKind resource) {
        return stacks.stream().filter(stack -> matches(stack, resource)).mapToInt(ItemStack::getCount).sum();
    }

    /** The original iron-delivery API retains its exact inventory and reward semantics. */
    public static Optional<Exchange> plan(Inventory inventory, QuestTerms terms) {
        return plan(inventory, List.of(new ResourceRequirement(ResourceKind.IRON_INGOT, terms.requiredIron())),
                terms.rewardEmeralds());
    }

    /**
     * Preflights all requirements and the entire reward on copies. An empty requirement list
     * plans a reward-only exchange, and a zero-emerald reward needs no free reward slot.
     */
    public static Optional<Exchange> plan(Inventory inventory, List<ResourceRequirement> requirements,
                                          int rewardEmeralds) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(requirements, "requirements");
        if (rewardEmeralds < 0) throw new IllegalArgumentException("Emerald reward cannot be negative");
        var resources = EnumSet.noneOf(ResourceKind.class);
        for (ResourceRequirement requirement : requirements) {
            Objects.requireNonNull(requirement, "requirement");
            if (!resources.add(requirement.resource())) {
                throw new IllegalArgumentException("Duplicate delivery resource: " + requirement.resource());
            }
        }
        List<ItemStack> items = copies(inventory.items);
        List<ItemStack> offhand = copies(inventory.offhand);
        for (ResourceRequirement requirement : requirements) {
            int remaining = consume(items, requirement.resource(), requirement.count());
            remaining = consume(offhand, requirement.resource(), remaining);
            if (remaining != 0) return Optional.empty();
        }

        ItemStack emerald = new ItemStack(Items.EMERALD);
        int remaining = rewardEmeralds;
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

    private static int consume(List<ItemStack> stacks, ResourceKind resource, int remaining) {
        for (int i = 0; i < stacks.size() && remaining > 0; i++) {
            ItemStack stack = stacks.get(i);
            if (!matches(stack, resource)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) stacks.set(i, ItemStack.EMPTY);
        }
        return remaining;
    }

    private static boolean matches(ItemStack stack, ResourceKind resource) {
        return switch (resource) {
            case IRON_INGOT -> stack.is(Items.IRON_INGOT);
            case WHEAT -> stack.is(Items.WHEAT);
            case LOGS -> stack.is(ItemTags.LOGS);
            case STONE -> stack.is(Items.STONE);
        };
    }

    public record Exchange(List<ItemStack> items, List<ItemStack> offhand) {
        public Exchange {
            items = List.copyOf(items);
            offhand = List.copyOf(offhand);
        }

        /** Call after the server-owned quest transition succeeds, without yielding between them. */
        public void apply(Inventory inventory) {
            if (inventory.items.size() != items.size() || inventory.offhand.size() != offhand.size()) {
                throw new IllegalArgumentException("Inventory shape differs from the planned exchange");
            }
            for (int i = 0; i < items.size(); i++) inventory.items.set(i, items.get(i).copy());
            for (int i = 0; i < offhand.size(); i++) inventory.offhand.set(i, offhand.get(i).copy());
            inventory.setChanged();
        }
    }
}

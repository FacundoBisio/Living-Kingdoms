package dev.livingkingdoms.quest;

import dev.livingkingdoms.quest.domain.QuestTerms;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.quest.expansion.domain.ResourceRequirement;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Native inventory rules without world access; datapack log tags are exercised in GameTests. */
class DeliveryInventoryTest {
    @BeforeAll static void bootstrapItems() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void countsMainInventoryAndOffhandButExcludesArmorAndSimilarItems() {
        Inventory inventory = inventory();
        inventory.items.set(0, new ItemStack(Items.IRON_INGOT, 7));
        inventory.offhand.set(0, new ItemStack(Items.IRON_INGOT, 3));
        inventory.armor.set(0, new ItemStack(Items.IRON_INGOT, 64));
        inventory.items.set(1, new ItemStack(Items.WHEAT, 8));
        inventory.items.set(2, new ItemStack(Items.WHEAT_SEEDS, 64));
        inventory.items.set(3, new ItemStack(Items.STONE, 9));
        inventory.items.set(4, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(10, DeliveryInventory.count(inventory, ResourceKind.IRON_INGOT));
        assertEquals(10, DeliveryInventory.ironCount(inventory));
        assertEquals(8, DeliveryInventory.count(inventory, ResourceKind.WHEAT));
        assertEquals(9, DeliveryInventory.count(inventory, ResourceKind.STONE));
    }

    @Test void multipleRequirementsRemainUntouchedUntilSuccessfulExchangeIsApplied() {
        Inventory inventory = inventory();
        inventory.items.set(0, new ItemStack(Items.IRON_INGOT, 4));
        inventory.offhand.set(0, new ItemStack(Items.IRON_INGOT, 8));
        inventory.items.set(1, new ItemStack(Items.WHEAT, 16));
        var exchange = DeliveryInventory.plan(inventory, List.of(required(ResourceKind.IRON_INGOT, 10),
                required(ResourceKind.WHEAT, 12)), 5).orElseThrow();
        assertEquals(12, DeliveryInventory.ironCount(inventory));
        assertEquals(16, DeliveryInventory.count(inventory, ResourceKind.WHEAT));
        assertEquals(0, emeralds(inventory));
        exchange.apply(inventory);
        assertEquals(2, DeliveryInventory.ironCount(inventory));
        assertEquals(4, DeliveryInventory.count(inventory, ResourceKind.WHEAT));
        assertEquals(5, emeralds(inventory));
    }

    @Test void missingLastRequirementDoesNotConsumeEarlierResources() {
        Inventory inventory = inventory();
        inventory.items.set(0, new ItemStack(Items.IRON_INGOT, 12));
        inventory.items.set(1, new ItemStack(Items.WHEAT, 3));
        assertTrue(DeliveryInventory.plan(inventory, List.of(required(ResourceKind.IRON_INGOT, 10),
                required(ResourceKind.WHEAT, 4)), 5).isEmpty());
        assertEquals(12, DeliveryInventory.ironCount(inventory));
        assertEquals(3, DeliveryInventory.count(inventory, ResourceKind.WHEAT));
        assertEquals(0, emeralds(inventory));
    }

    @Test void fullRewardCapacityRefusesWithoutConsumingAnyRequirement() {
        Inventory inventory = fullInventory();
        inventory.items.set(0, new ItemStack(Items.WHEAT, 16));
        inventory.offhand.set(0, new ItemStack(Items.IRON_INGOT, 16));
        assertTrue(DeliveryInventory.plan(inventory, List.of(required(ResourceKind.IRON_INGOT, 8),
                required(ResourceKind.WHEAT, 8)), 1).isEmpty());
        assertEquals(16, DeliveryInventory.ironCount(inventory));
        assertEquals(16, DeliveryInventory.count(inventory, ResourceKind.WHEAT));
        assertEquals(0, emeralds(inventory));
        assertEquals(64, inventory.items.get(1).getCount());
    }

    @Test void zeroRewardNeedsNoMainInventorySpace() {
        Inventory inventory = fullInventory();
        inventory.offhand.set(0, new ItemStack(Items.WHEAT, 16));
        DeliveryInventory.plan(inventory, List.of(required(ResourceKind.WHEAT, 8)), 0).orElseThrow().apply(inventory);
        assertEquals(8, DeliveryInventory.count(inventory, ResourceKind.WHEAT));
        assertEquals(0, emeralds(inventory));
        assertTrue(inventory.items.stream().allMatch(stack -> stack.is(Items.COBBLESTONE) && stack.getCount() == 64));
    }

    @Test void rewardOnlyExchangeMergesAndSplitsWithoutResources() {
        Inventory inventory = inventory();
        inventory.items.set(0, new ItemStack(Items.EMERALD, 60));
        DeliveryInventory.plan(inventory, List.of(), 80).orElseThrow().apply(inventory);
        assertEquals(140, emeralds(inventory));
        assertEquals(64, inventory.items.get(0).getCount());
        assertEquals(64, inventory.items.get(1).getCount());
        assertEquals(12, inventory.items.get(2).getCount());
        assertTrue(DeliveryInventory.plan(fullInventory(), List.of(), 1).isEmpty());
    }

    @Test void componentsSurvivePartialConsumptionAndPreventIncompatibleEmeraldMerging() {
        Inventory inventory = inventory();
        ItemStack wheat = new ItemStack(Items.WHEAT, 16);
        wheat.set(DataComponents.CUSTOM_NAME, Component.literal("Harvest reserve"));
        ItemStack emerald = new ItemStack(Items.EMERALD, 60);
        emerald.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
        ItemStack diamond = new ItemStack(Items.DIAMOND, 3);
        diamond.set(DataComponents.CUSTOM_NAME, Component.literal("Unrelated"));
        inventory.items.set(0, wheat);
        inventory.items.set(1, emerald);
        inventory.items.set(2, diamond);
        DeliveryInventory.plan(inventory, List.of(required(ResourceKind.WHEAT, 8)), 8).orElseThrow().apply(inventory);
        assertEquals(8, inventory.items.get(0).getCount());
        assertEquals(wheat.get(DataComponents.CUSTOM_NAME), inventory.items.get(0).get(DataComponents.CUSTOM_NAME));
        assertEquals(60, inventory.items.get(1).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(emerald, inventory.items.get(1)));
        assertEquals(3, inventory.items.get(2).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(diamond, inventory.items.get(2)));
        assertEquals(68, emeralds(inventory));
        assertNull(inventory.items.get(3).get(DataComponents.CUSTOM_NAME));
        assertEquals(16, wheat.getCount(), "Planning and applying must not mutate original stack references");
    }

    @Test void duplicateRequirementsAndNegativeRewardRejectWithoutMutation() {
        Inventory inventory = inventory();
        inventory.items.set(0, new ItemStack(Items.IRON_INGOT, 16));
        assertThrows(IllegalArgumentException.class, () -> DeliveryInventory.plan(inventory,
                List.of(required(ResourceKind.IRON_INGOT, 8), required(ResourceKind.IRON_INGOT, 8)), 0));
        assertThrows(IllegalArgumentException.class, () -> DeliveryInventory.plan(inventory, List.of(), -1));
        assertEquals(16, DeliveryInventory.ironCount(inventory));
    }

    @Test void legacyIronApiRetainsExactConsumptionAndLargerRewardSupport() {
        Inventory inventory = inventory();
        inventory.items.set(0, new ItemStack(Items.IRON_INGOT, 20));
        DeliveryInventory.plan(inventory, new QuestTerms(16, 80, 10)).orElseThrow().apply(inventory);
        assertEquals(4, DeliveryInventory.ironCount(inventory));
        assertEquals(80, emeralds(inventory));
    }

    private static Inventory inventory() { return new Inventory(null); }
    private static Inventory fullInventory() {
        Inventory inventory = inventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) inventory.items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        return inventory;
    }
    private static ResourceRequirement required(ResourceKind resource, int count) { return new ResourceRequirement(resource, count); }
    private static int emeralds(Inventory inventory) {
        return inventory.items.stream().filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }
}

package dev.livingkingdoms.gametest;

import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.expansion.domain.ResourceKind;
import dev.livingkingdoms.quest.expansion.domain.ResourceRequirement;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Real server datapack tags; these inventory-only fixtures neither load chunks nor spawn entities. */
@GameTestHolder(KingdomGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestExpansionInventoryGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void logTagVariantsAndAllResourcesExchangeExactly(GameTestHelper helper) {
        Inventory inventory = new Inventory(null);
        inventory.items.set(0, new ItemStack(Items.OAK_LOG, 3));
        inventory.items.set(1, new ItemStack(Items.BIRCH_LOG, 4));
        inventory.items.set(2, new ItemStack(Items.STRIPPED_SPRUCE_LOG, 5));
        inventory.items.set(3, new ItemStack(Items.CHERRY_WOOD, 6));
        inventory.items.set(4, new ItemStack(Items.CRIMSON_STEM, 7));
        ItemStack offhand = new ItemStack(Items.WARPED_HYPHAE, 8);
        offhand.set(DataComponents.CUSTOM_NAME, Component.literal("Building reserve"));
        inventory.offhand.set(0, offhand);
        inventory.armor.set(0, new ItemStack(Items.OAK_LOG, 64));
        inventory.items.set(5, new ItemStack(Items.STONE, 10));
        inventory.items.set(6, new ItemStack(Items.WHEAT, 12));
        inventory.items.set(7, new ItemStack(Items.IRON_INGOT, 7));
        inventory.items.set(8, new ItemStack(Items.COBBLESTONE, 32));
        ItemStack unrelated = new ItemStack(Items.DIAMOND, 3);
        unrelated.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this stack"));
        inventory.items.set(9, unrelated);
        helper.assertTrue(DeliveryInventory.count(inventory, ResourceKind.LOGS) == 33,
                "Logs must use the server tag, including wood, stripped logs and Nether stems, while ignoring armor");
        var exchange = DeliveryInventory.plan(inventory, List.of(required(ResourceKind.LOGS, 30),
                required(ResourceKind.STONE, 6), required(ResourceKind.WHEAT, 8), required(ResourceKind.IRON_INGOT, 5)), 8);
        helper.assertTrue(exchange.isPresent(), "All four resources and reward capacity must preflight together");
        helper.assertTrue(DeliveryInventory.count(inventory, ResourceKind.LOGS) == 33 && emeralds(inventory) == 0,
                "Planning must leave live inventory untouched");
        exchange.orElseThrow().apply(inventory);
        helper.assertTrue(DeliveryInventory.count(inventory, ResourceKind.LOGS) == 3
                && DeliveryInventory.count(inventory, ResourceKind.STONE) == 4
                && DeliveryInventory.count(inventory, ResourceKind.WHEAT) == 4
                && DeliveryInventory.ironCount(inventory) == 2 && emeralds(inventory) == 8,
                "Applying must remove exactly each requested resource across main inventory and offhand");
        helper.assertTrue(inventory.offhand.get(0).is(Items.WARPED_HYPHAE)
                && inventory.offhand.get(0).getCount() == 3
                && ItemStack.isSameItemSameComponents(offhand, inventory.offhand.get(0)),
                "A partially consumed tagged log must retain its components");
        helper.assertTrue(inventory.armor.get(0).getCount() == 64
                && inventory.items.get(8).is(Items.COBBLESTONE) && inventory.items.get(8).getCount() == 32
                && inventory.items.get(9).getCount() == 3 && ItemStack.isSameItemSameComponents(unrelated, inventory.items.get(9)),
                "Armor, cobblestone and unrelated component-bearing items must remain unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void missingStoneFullInventoryAndArmorCannotConsumeLogs(GameTestHelper helper) {
        Inventory inventory = new Inventory(null);
        inventory.items.set(0, new ItemStack(Items.OAK_LOG, 16));
        inventory.items.set(1, new ItemStack(Items.COBBLESTONE, 64));
        inventory.armor.set(0, new ItemStack(Items.STONE, 64));
        helper.assertTrue(DeliveryInventory.count(inventory, ResourceKind.STONE) == 0
                && DeliveryInventory.plan(inventory, List.of(required(ResourceKind.LOGS, 8), required(ResourceKind.STONE, 2)), 4).isEmpty()
                && DeliveryInventory.count(inventory, ResourceKind.LOGS) == 16,
                "Cobblestone and armor stone must not satisfy a stone objective or cause earlier log consumption");
        for (int slot = 0; slot < inventory.items.size(); slot++) inventory.items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        inventory.offhand.set(0, new ItemStack(Items.STRIPPED_OAK_LOG, 20));
        helper.assertTrue(DeliveryInventory.plan(inventory, List.of(required(ResourceKind.LOGS, 8)), 1).isEmpty()
                && DeliveryInventory.count(inventory, ResourceKind.LOGS) == 20 && emeralds(inventory) == 0,
                "An offhand delivery cannot consume logs when the emerald reward does not fit in main inventory");
        DeliveryInventory.plan(inventory, List.of(required(ResourceKind.LOGS, 8)), 0).orElseThrow().apply(inventory);
        helper.assertTrue(DeliveryInventory.count(inventory, ResourceKind.LOGS) == 12
                && inventory.items.stream().allMatch(stack -> stack.is(Items.COBBLESTONE) && stack.getCount() == 64),
                "A reputation-only objective with zero emeralds may consume exact offhand logs in a full inventory");
        helper.succeed();
    }

    private static ResourceRequirement required(ResourceKind resource, int count) { return new ResourceRequirement(resource, count); }
    private static int emeralds(Inventory inventory) {
        return inventory.items.stream().filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }
}

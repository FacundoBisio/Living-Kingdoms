package dev.livingkingdoms.commerce;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CommerceInventoryTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void offhandEmeraldsPayExactlyOnceAndInputOnlyChangesOnApply() {
        var inventory=new Inventory(null);inventory.offhand.set(0,new ItemStack(Items.EMERALD,8));
        var plan=CommerceInventory.plan(inventory,Items.EMERALD,3,Items.IRON_INGOT,1).orElseThrow();
        assertEquals(8,inventory.offhand.getFirst().getCount());assertTrue(inventory.items.getFirst().isEmpty());
        plan.apply(inventory);assertEquals(5,inventory.offhand.getFirst().getCount());assertTrue(inventory.items.getFirst().is(Items.IRON_INGOT));
    }
    @Test void namedOrDamagedEquipmentCannotBecomeCanonicalStock() {
        var inventory=new Inventory(null);var gear=new ItemStack(Items.IRON_CHESTPLATE);gear.setDamageValue(1);inventory.items.set(0,gear);
        assertTrue(CommerceInventory.plan(inventory,Items.IRON_CHESTPLATE,1,Items.EMERALD,8).isEmpty());
        gear.setDamageValue(0);gear.set(DataComponents.CUSTOM_NAME,Component.literal("Keepsake"));
        assertTrue(CommerceInventory.plan(inventory,Items.IRON_CHESTPLATE,1,Items.EMERALD,8).isEmpty());
        assertEquals("Keepsake",gear.getHoverName().getString());
    }
    @Test void fullInventoryCanReuseFullyConsumedInputButCannotDropOverflowRewards() {
        var inventory=new Inventory(null);for(int i=0;i<inventory.items.size();i++)inventory.items.set(i,new ItemStack(Items.DIRT,64));
        inventory.items.set(0,new ItemStack(Items.EMERALD,3));
        assertTrue(CommerceInventory.plan(inventory,Items.EMERALD,2,Items.IRON_INGOT,1).isEmpty());assertEquals(3,inventory.items.getFirst().getCount());
        var plan=CommerceInventory.plan(inventory,Items.EMERALD,3,Items.IRON_INGOT,1).orElseThrow();plan.apply(inventory);assertTrue(inventory.items.getFirst().is(Items.IRON_INGOT));
    }
}

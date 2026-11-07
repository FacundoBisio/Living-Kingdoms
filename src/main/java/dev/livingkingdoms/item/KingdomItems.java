package dev.livingkingdoms.item;

import dev.livingkingdoms.LivingKingdoms;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class KingdomItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LivingKingdoms.MOD_ID);
    public static final DeferredItem<KingdomCharterItem> KINGDOM_CHARTER = ITEMS.registerItem("kingdom_charter",
            KingdomCharterItem::new,new Item.Properties().stacksTo(16));
    public static final DeferredItem<RoyalWhetstoneItem> ROYAL_WHETSTONE = ITEMS.registerItem("royal_whetstone",
            RoyalWhetstoneItem::new,new Item.Properties().stacksTo(16).rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    public static final DeferredItem<KingdomChestItem> CHEST_COMMON = chest(dev.livingkingdoms.rpg.domain.LootRarity.COMMON, net.minecraft.world.item.Rarity.COMMON);
    public static final DeferredItem<KingdomChestItem> CHEST_RARE = chest(dev.livingkingdoms.rpg.domain.LootRarity.RARE, net.minecraft.world.item.Rarity.UNCOMMON);
    public static final DeferredItem<KingdomChestItem> CHEST_EPIC = chest(dev.livingkingdoms.rpg.domain.LootRarity.EPIC, net.minecraft.world.item.Rarity.RARE);
    public static final DeferredItem<KingdomChestItem> CHEST_LEGENDARY = chest(dev.livingkingdoms.rpg.domain.LootRarity.LEGENDARY, net.minecraft.world.item.Rarity.EPIC);
    private static DeferredItem<KingdomChestItem> chest(dev.livingkingdoms.rpg.domain.LootRarity rarity, net.minecraft.world.item.Rarity display) {
        return ITEMS.registerItem("kingdom_chest_" + rarity.id(), properties -> new KingdomChestItem(rarity, properties),
                new Item.Properties().stacksTo(16).rarity(display));
    }
    public static void register(IEventBus bus) { ITEMS.register(bus); bus.addListener(KingdomItems::creativeTab); }
    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) { event.accept(KINGDOM_CHARTER); event.accept(ROYAL_WHETSTONE); event.accept(CHEST_COMMON); event.accept(CHEST_RARE); event.accept(CHEST_EPIC); event.accept(CHEST_LEGENDARY); }
    }
    private KingdomItems() {}
}

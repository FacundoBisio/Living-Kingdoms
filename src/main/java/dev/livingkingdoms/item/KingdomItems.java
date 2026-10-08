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
    public static void register(IEventBus bus) { ITEMS.register(bus); bus.addListener(KingdomItems::creativeTab); }
    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(KINGDOM_CHARTER);
    }
    private KingdomItems() {}
}

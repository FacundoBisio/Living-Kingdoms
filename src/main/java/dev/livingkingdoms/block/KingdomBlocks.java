package dev.livingkingdoms.block;

import com.mojang.serialization.MapCodec;
import dev.livingkingdoms.LivingKingdoms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registry ownership only; gameplay rules stay in block/service classes. */
public final class KingdomBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LivingKingdoms.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LivingKingdoms.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends Block>> BLOCK_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_TYPE, LivingKingdoms.MOD_ID);
    public static final DeferredBlock<QuestBoardBlock> QUEST_BOARD = BLOCKS.registerBlock("quest_board",
            QuestBoardBlock::new, BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.WOOD).noOcclusion());

    static {
        ITEMS.registerSimpleBlockItem(QUEST_BOARD);
        BLOCK_TYPES.register("quest_board", () -> QuestBoardBlock.CODEC);
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_TYPES.register(bus);
        bus.addListener(KingdomBlocks::creativeTab);
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(QUEST_BOARD);
    }

    private KingdomBlocks() {}
}

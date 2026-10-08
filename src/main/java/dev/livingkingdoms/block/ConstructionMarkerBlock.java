package dev.livingkingdoms.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Saved-project interaction point. It has no craftable item or drops. */
public final class ConstructionMarkerBlock extends Block {
    public static final MapCodec<ConstructionMarkerBlock> CODEC=simpleCodec(ConstructionMarkerBlock::new);
    public ConstructionMarkerBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<ConstructionMarkerBlock> codec() { return CODEC; }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,net.minecraft.world.level.BlockGetter level,BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.or(Block.box(2,0,6,4,16,10),Block.box(12,0,6,14,16,10),Block.box(4,8,7,12,15,9));
    }
    private static void open(Level level,Player player,BlockPos pos) {
        if(player instanceof ServerPlayer serverPlayer) dev.livingkingdoms.ui.VillageUiService.openConstructionMarker(serverPlayer,pos);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        open(level,player,pos); return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit) {
        if(hand==InteractionHand.MAIN_HAND) open(level,player,pos); return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
}

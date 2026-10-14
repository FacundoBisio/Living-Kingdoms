package dev.livingkingdoms.block;

import com.mojang.serialization.MapCodec;
import dev.livingkingdoms.storage.KingdomStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;

public final class KingdomStorageBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<KingdomStorageBlock> CODEC=simpleCodec(KingdomStorageBlock::new);
    public KingdomStorageBlock(Properties properties) {super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<KingdomStorageBlock> codec() {return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {builder.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {return defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite());}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer server) KingdomStorage.open(server,pos);return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit) {
        if(hand==InteractionHand.MAIN_HAND && player instanceof ServerPlayer server) KingdomStorage.open(server,pos);return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
}

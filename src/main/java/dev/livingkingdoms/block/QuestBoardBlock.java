package dev.livingkingdoms.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

/** Thin interaction adapter; quest and inventory decisions belong to the server service. */
public final class QuestBoardBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<QuestBoardBlock> CODEC = simpleCodec(QuestBoardBlock::new);
    private static final VoxelShape NORTH_SOUTH = Shapes.or(
            Block.box(1, 0, 5, 5, 1.5, 11), Block.box(11, 0, 5, 15, 1.5, 11),
            Block.box(2, 1.5, 6, 4, 5, 10), Block.box(12, 1.5, 6, 14, 5, 10),
            Block.box(2, 3, 6.5, 14, 4.5, 9.5), Block.box(1, 5, 5, 15, 14.5, 11),
            Block.box(0, 14.5, 4, 16, 16, 12));
    private static final VoxelShape EAST_WEST = Shapes.or(
            Block.box(5, 0, 1, 11, 1.5, 5), Block.box(5, 0, 11, 11, 1.5, 15),
            Block.box(6, 1.5, 2, 10, 5, 4), Block.box(6, 1.5, 12, 10, 5, 14),
            Block.box(6.5, 3, 2, 9.5, 4.5, 14), Block.box(5, 5, 1, 11, 14.5, 15),
            Block.box(4, 14.5, 0, 12, 16, 16));

    public QuestBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<QuestBoardBlock> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? NORTH_SOUTH : EAST_WEST;
    }

    private static void interact(Level level, Player player, BlockPos pos) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            dev.livingkingdoms.ui.VillageUiService.openBoard(serverPlayer, pos);
        }
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                          Player player, BlockHitResult hit) {
        interact(level, player, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                        Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand == InteractionHand.MAIN_HAND) interact(level, player, pos);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
}

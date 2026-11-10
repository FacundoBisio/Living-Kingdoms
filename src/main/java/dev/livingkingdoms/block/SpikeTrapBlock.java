package dev.livingkingdoms.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A low bed of iron spikes. Monsters walking over it take 3 damage every half second and are slowed;
 * players, villagers and animals cross it unharmed. No collision, so pathing mobs walk straight in.
 */
public class SpikeTrapBlock extends Block {
    public static final MapCodec<SpikeTrapBlock> CODEC = simpleCodec(SpikeTrapBlock::new);
    public static final float DAMAGE = 3F;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 5, 16);
    public SpikeTrapBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return true; }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) { return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP); }
    @Override protected BlockState updateShape(BlockState state, net.minecraft.core.Direction direction, BlockState neighbor, net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction == net.minecraft.core.Direction.DOWN && !canSurvive(state, level, pos) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }
    @Override protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(level instanceof ServerLevel server) || !(entity instanceof LivingEntity living) || !(entity instanceof Enemy)) return;
        living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, true));
        if (living.invulnerableTime > 0 || Math.floorMod(level.getGameTime() + pos.asLong(), 10) != 0) return;
        if (living.hurt(level.damageSources().cactus(), DAMAGE)) {
            server.sendParticles(ParticleTypes.CRIT, entity.getX(), entity.getY() + .3, entity.getZ(), 5, .2, .1, .2, .1);
            level.playSound(null, pos, SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, SoundSource.BLOCKS, .6F, 1.2F);
        }
    }
}

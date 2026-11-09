package dev.livingkingdoms.item;

import dev.livingkingdoms.rpg.RefinementService;
import dev.livingkingdoms.rpg.domain.RefinementRules;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;

/**
 * Hold the whetstone in one hand and a sword/axe/trident/mace in the other, then use the whetstone.
 * Each use raises refinement by one, up to {@link RefinementRules#MAX_LEVEL}, and costs experience levels.
 */
public final class RoyalWhetstoneItem extends Item {
    public RoyalWhetstoneItem(Properties properties) { super(properties); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stone = player.getItemInHand(hand);
        ItemStack weapon = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (level.isClientSide) return RefinementService.refinable(weapon) ? InteractionResultHolder.success(stone) : InteractionResultHolder.pass(stone);
        if (!(player instanceof ServerPlayer server)) return InteractionResultHolder.pass(stone);
        if (!RefinementService.refinable(weapon)) {
            server.displayClientMessage(Component.translatable("item.livingkingdoms.royal_whetstone.no_weapon"), true);
            return InteractionResultHolder.fail(stone);
        }
        int current = RefinementService.level(weapon);
        if (!RefinementRules.canRefine(current)) {
            server.displayClientMessage(Component.translatable("item.livingkingdoms.royal_whetstone.maxed"), true);
            return InteractionResultHolder.fail(stone);
        }
        int cost = RefinementRules.experienceCost(current);
        if (dev.livingkingdoms.rpg.TechniqueService.knows(server, dev.livingkingdoms.rpg.domain.Technique.MASTER_SMITH)) cost = Math.max(1, cost - 1);
        if (!server.getAbilities().instabuild && server.experienceLevel < cost) {
            server.displayClientMessage(Component.translatable("item.livingkingdoms.royal_whetstone.levels", cost), true);
            return InteractionResultHolder.fail(stone);
        }
        if (!server.getAbilities().instabuild) { server.giveExperienceLevels(-cost); stone.shrink(1); }
        RefinementService.apply(weapon, current + 1);
        var world = (ServerLevel) level;
        world.playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1F, 0.9F + current * 0.08F);
        if (current + 1 >= RefinementRules.MAX_LEVEL)
            world.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.2F);
        world.sendParticles(ParticleTypes.ENCHANTED_HIT, player.getX(), player.getY() + 1.2, player.getZ(), 18, 0.4, 0.4, 0.4, 0.15);
        world.sendParticles(ParticleTypes.WAX_ON, player.getX(), player.getY() + 1.0, player.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
        dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(server, "first_refinement");
        server.displayClientMessage(Component.translatable("item.livingkingdoms.royal_whetstone.refined",
                weapon.getHoverName(), current + 1, RefinementRules.MAX_LEVEL), true);
        return InteractionResultHolder.consume(stone);
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.livingkingdoms.royal_whetstone.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}

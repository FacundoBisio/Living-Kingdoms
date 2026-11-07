package dev.livingkingdoms.item;

import dev.livingkingdoms.rpg.LootChests;
import dev.livingkingdoms.rpg.RewardCeremony;
import dev.livingkingdoms.rpg.domain.LootRarity;
import net.minecraft.network.chat.Component;
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

/** Use to open: contents are rolled on the server at open time and celebrated with a rarity banner. */
public final class KingdomChestItem extends Item {
    private final LootRarity rarity;
    public KingdomChestItem(LootRarity rarity, Properties properties) { super(properties); this.rarity = rarity; }
    public LootRarity rarity() { return rarity; }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer server)) return InteractionResultHolder.success(stack);
        stack.consume(1, server);
        level.playSound(null, player.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 1F, 1F);
        RewardCeremony.celebrate(server, "chest", Component.translatable("item.livingkingdoms.kingdom_chest_" + rarity.id()), 0, 0, 0,
                LootChests.roll(server, rarity), rarity.ordinal() >= LootRarity.EPIC.ordinal(), rarity);
        return InteractionResultHolder.consume(stack);
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.livingkingdoms.kingdom_chest.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}

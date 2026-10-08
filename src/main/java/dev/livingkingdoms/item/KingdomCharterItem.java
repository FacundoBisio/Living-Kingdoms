package dev.livingkingdoms.item;

import dev.livingkingdoms.settlement.SettlementEstablishmentService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/** Client predicts only the hand animation. The server owns settlement creation and inventory changes. */
public final class KingdomCharterItem extends Item {
    public KingdomCharterItem(Properties properties) { super(properties); }
    // Run before vanilla block interaction and outside CommonHooks' block-only rollback.
    // Establishment posts placement events inside its own blocks + data + NPC transaction.
    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getPlayer() != null && context.getPlayer().getCooldowns().isOnCooldown(this)) return InteractionResult.FAIL;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        var result = SettlementEstablishmentService.useCharter(player,context.getHand(),context.getClickedPos(),
                context.isSecondaryUseActive() ? SettlementEstablishmentService.Mode.CONVERT_ONLY : SettlementEstablishmentService.Mode.AUTO);
        player.displayClientMessage(result.message(),result.successful());
        // A short cooldown bounds repeated surveys, including unsuccessful attempts.
        player.getCooldowns().addCooldown(this,20);
        return result.successful() ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.livingkingdoms.kingdom_charter.tooltip").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.livingkingdoms.kingdom_charter.convert_only").withStyle(ChatFormatting.DARK_GRAY));
    }
}

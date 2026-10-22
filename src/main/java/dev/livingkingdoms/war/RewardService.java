package dev.livingkingdoms.war;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.config.WarConfig;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.war.domain.Bounty;
import dev.livingkingdoms.war.domain.RenownLedger;
import dev.livingkingdoms.war.domain.RenownRank;
import dev.livingkingdoms.war.persistence.WarSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Party bounties and player renown. Server authoritative; rank rewards are one-time persisted receipts. */
public final class RewardService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RewardService() {}

    /** Drops the bounty where the last member fell. Called once per defeated, reward-eligible party. */
    public static void payBounty(ServerLevel level, HostileParty party, int strength, BlockPos position) {
        if (!KingdomConfig.SPEC.isLoaded() || !WarConfig.BOUNTY_ENABLED.get()) return;
        Bounty bounty = Bounty.forParty(party.type(), party.memberIds().size(), party.levels().average(), strength, WarConfig.bountyRules());
        if (bounty.empty()) return;
        Vec3 at = Vec3.atCenterOf(position);
        if (bounty.emeralds() > 0) drop(level, at, new ItemStack(Items.EMERALD, bounty.emeralds()));
        if (bounty.iron() > 0) drop(level, at, new ItemStack(Items.IRON_INGOT, bounty.iron()));
        if (bounty.experience() > 0) ExperienceOrb.award(level, at, bounty.experience());
    }

    /** Every eligible participant earns the full renown, so cooperative play is never penalised. */
    public static void awardRenown(MinecraftServer server, HostileParty party, Set<UUID> participants) {
        if (!KingdomConfig.SPEC.isLoaded() || !WarConfig.RENOWN_ENABLED.get()) return;
        int points = RenownLedger.pointsForDefeat(party.type(), party.memberIds().size(), party.levels().average());
        if (points <= 0) return;
        WarSavedData data = WarSavedData.get(server);
        for (UUID participant : participants) {
            RenownLedger ledger = data.ledger(participant).add(points);
            if (!data.putLedger(participant, ledger)) {
                LOGGER.warn("Renown store is full; player {} earned no renown", participant);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(participant);
            if (player == null) continue;
            player.displayClientMessage(Component.translatable("renown.livingkingdoms.gained", points, ledger.points(),
                    Component.translatable(ledger.rank().titleKey())), true);
            deliverPending(player);
        }
    }

    /** Hands out every earned, not yet rewarded rank. The receipt is written before the items are given. */
    public static void deliverPending(ServerPlayer player) {
        if (!KingdomConfig.SPEC.isLoaded() || !WarConfig.RENOWN_ENABLED.get()) return;
        WarSavedData data = WarSavedData.get(player.server);
        RenownLedger ledger = data.ledger(player.getUUID());
        List<RenownRank> pending = ledger.pending();
        if (pending.isEmpty()) return;
        data.putLedger(player.getUUID(), ledger.rewardedThrough(pending.getLast()));
        for (RenownRank rank : pending) {
            for (ItemStack stack : rewards(rank)) give(player, stack);
            player.displayClientMessage(Component.translatable("renown.livingkingdoms.rank_up",
                    Component.translatable(rank.titleKey()).withStyle(ChatFormatting.GOLD)), false);
        }
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) deliverPending(player);
    }

    /** Fixed, vanilla-only rewards; each rank is paid once per player per world. */
    public static List<ItemStack> rewards(RenownRank rank) {
        return switch (rank) {
            case RECRUIT -> List.of();
            case SQUIRE -> List.of(new ItemStack(Items.EMERALD, 8), new ItemStack(Items.IRON_SWORD));
            case SERGEANT -> List.of(new ItemStack(Items.EMERALD, 12), new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_CHESTPLATE));
            case KNIGHT -> List.of(new ItemStack(Items.EMERALD, 20), new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_HORSE_ARMOR));
            case CAPTAIN -> List.of(new ItemStack(Items.EMERALD, 32), new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.GOLDEN_APPLE, 2));
            case WARLORD -> List.of(new ItemStack(Items.EMERALD, 64), new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.TOTEM_OF_UNDYING));
        };
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
    }

    private static void drop(ServerLevel level, Vec3 at, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, at.x, at.y + 0.25, at.z, stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }
}

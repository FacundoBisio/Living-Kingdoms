package dev.livingkingdoms.quest;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.KingdomConfig;
import dev.livingkingdoms.npc.NpcService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.domain.QuestTerms;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Optional;

/** One server-owned resource-delivery loop, invoked by the existing board. */
public final class QuestService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private QuestService() {}

    public static QuestTerms currentTerms() {
        return new QuestTerms(KingdomConfig.QUEST_REQUIRED_IRON.get(), KingdomConfig.QUEST_REWARD_EMERALDS.get(),
                KingdomConfig.QUEST_REPUTATION_REWARD.get());
    }

    public static void interact(ServerPlayer player, BlockPos board, boolean sneaking) {
        var level = player.serverLevel();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Quest interaction requires the server thread");
        if (player.isSpectator() || !player.isAlive() || player.distanceToSqr(Vec3.atCenterOf(board)) > 64
                || !level.getBlockState(board).is(KingdomBlocks.QUEST_BOARD)) return;
        var settlements = SettlementSavedData.get(level.getServer());
        String dimension = level.dimension().location().toString();
        Optional<Settlement> found = settlements.at(dimension, board.getX(), board.getZ());
        BlockPos pos = player.blockPosition();
        if (found.isEmpty() || !found.orElseThrow().faction().isAllied()
                || !found.orElseThrow().territory().contains(dimension, pos.getX(), pos.getZ())) {
            message(player, "quest.livingkingdoms.no_settlement");
            return;
        }
        Settlement settlement = found.orElseThrow();
        var data = QuestSavedData.get(level.getServer());
        // M1 worlds acquire their Mayor when their existing physical settlement's board is used.
        BlockPos marker = new BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        if (data.mayor(settlement.id()).isEmpty()
                && level.getChunkSource().hasChunk(marker.getX() >> 4, marker.getZ() >> 4)
                && level.getBlockState(marker).is(Blocks.LODESTONE)) {
            try {
                if (NpcService.ensureMayor(level, settlement).isEmpty()) {
                    LOGGER.warn("No safe loaded Mayor location for settlement {}", settlement.id());
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Could not associate Mayor for settlement {}", settlement.id(), exception);
            }
        }
        var progress = data.progress(player.getUUID(), settlement.id());
        if (!sneaking) {
            inspect(player, settlement, progress.state(), progress.terms(), progress.reputation());
            return;
        }
        switch (progress.state()) {
            case AVAILABLE -> {
                // Freeze this player's offer; later config changes cannot change an accepted quest.
                if (data.accept(player.getUUID(), settlement.id(), currentTerms())) {
                    message(player, "quest.livingkingdoms.accepted", title());
                }
                var active = data.progress(player.getUUID(), settlement.id());
                inspect(player, settlement, active.state(), active.terms(), active.reputation());
            }
            case ACTIVE -> {
                QuestTerms terms = progress.terms();
                int iron = DeliveryInventory.ironCount(player.getInventory());
                if (iron < terms.requiredIron()) {
                    message(player, "quest.livingkingdoms.insufficient", iron, terms.requiredIron());
                    return;
                }
                var exchange = DeliveryInventory.plan(player.getInventory(), terms);
                if (exchange.isEmpty()) {
                    message(player, "quest.livingkingdoms.inventory_full");
                    return;
                }
                // Single server-thread transition guards the reward; all slot changes were preflighted.
                if (!data.complete(player.getUUID(), settlement.id())) return;
                exchange.orElseThrow().apply(player.getInventory());
                player.inventoryMenu.broadcastChanges();
                if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
                message(player, "quest.livingkingdoms.delivered", terms.requiredIron(), terms.rewardEmeralds(), terms.reputationReward());
                message(player, "quest.livingkingdoms.reputation", settlement.name(), data.progress(player.getUUID(), settlement.id()).reputation());
            }
            case COMPLETED -> message(player, "quest.livingkingdoms.already_completed", title());
            case FAILED -> message(player, "quest.livingkingdoms.failed", title());
        }
    }

    private static void inspect(ServerPlayer player, Settlement settlement, QuestState state, QuestTerms savedTerms, int reputation) {
        QuestTerms terms = savedTerms == null ? currentTerms() : savedTerms;
        message(player, "quest.livingkingdoms.header", settlement.name(), title(),
                Component.translatable("quest.livingkingdoms.state." + state.name().toLowerCase(java.util.Locale.ROOT)));
        if (state == QuestState.AVAILABLE || state == QuestState.ACTIVE) {
            int count = DeliveryInventory.ironCount(player.getInventory());
            message(player, "quest.livingkingdoms.objective", terms.requiredIron(), Math.min(count, terms.requiredIron()));
            message(player, "quest.livingkingdoms.rewards", terms.rewardEmeralds(), terms.reputationReward());
            message(player, state == QuestState.AVAILABLE ? "quest.livingkingdoms.help.accept" : "quest.livingkingdoms.help.deliver");
        }
        message(player, "quest.livingkingdoms.reputation", settlement.name(), reputation);
    }

    private static Component title() { return Component.translatable("quest.livingkingdoms.iron_shortage.title"); }

    private static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), false);
    }
}

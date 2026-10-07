package dev.livingkingdoms.npc;

import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Cancels vanilla trading/item use while keeping all NPC dialogue decisions on the server. */
public final class NpcInteractions {
    private NpcInteractions() {}

    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!isLivingKingdomsNpc(event.getTarget())) return;
        event.setCanceled(true);
        // The general interaction sends dialogue. PASS lets the client send that packet once.
        event.setCancellationResult(InteractionResult.PASS);
    }

    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!isLivingKingdomsNpc(event.getTarget())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (!(event.getLevel() instanceof ServerLevel level) || event.getHand() != InteractionHand.MAIN_HAND) return;
        NpcIdentity identity = NpcIdentity.read(event.getTarget()).orElseThrow();
        if (identity.role() != NpcRole.MAYOR) return;
        var settlement = SettlementSavedData.get(level.getServer()).settlements().stream()
                .filter(candidate -> candidate.id().equals(identity.settlementId())).findFirst();
        if (settlement.isEmpty()) return;
        QuestSavedData quests = QuestSavedData.get(level.getServer());
        if (quests.mayor(identity.settlementId()).filter(event.getTarget().getUUID()::equals).isEmpty()) return;
        String key = quests.progress(event.getEntity().getUUID(), identity.settlementId()).state() == QuestState.COMPLETED
                ? "npc.livingkingdoms.mayor.after" : "npc.livingkingdoms.mayor.before";
        event.getEntity().displayClientMessage(Component.translatable(key, settlement.get().name()), false);
    }

    private static boolean isLivingKingdomsNpc(Entity entity) {
        if (!(entity instanceof Villager villager)) return false;
        if (!entity.level().isClientSide) return NpcIdentity.read(entity).isPresent();
        // UUID/role tags are deliberately not client gameplay state. This synchronized name only
        // suppresses vanilla interaction prediction; the server still validates the real identity.
        Component name = villager.getCustomName();
        return villager.isNoAi() && name != null && name.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("npc.livingkingdoms.mayor.name");
    }
}

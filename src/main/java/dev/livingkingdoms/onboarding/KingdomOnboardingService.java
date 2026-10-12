package dev.livingkingdoms.onboarding;

import dev.livingkingdoms.npc.NpcIdentity;
import dev.livingkingdoms.npc.NpcRole;
import dev.livingkingdoms.onboarding.domain.KingdomName;
import dev.livingkingdoms.onboarding.domain.OnboardingStage;
import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.QuestTemplate;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.UUID;

/** Server-owned mayor conversation. UI tokens are validated by VillageUiService; principal and reach are checked again here. */
public final class KingdomOnboardingService {
    private KingdomOnboardingService() {}

    public static OnboardingStage stage(MinecraftServer server, UUID player, UUID settlement) {
        return KingdomOnboardingSavedData.get(server).stage(player, settlement);
    }

    public static boolean canHelp(ServerPlayer player, Settlement settlement) {
        if (!settlement.faction().isAllied()) return false;
        var onboarding = KingdomOnboardingSavedData.get(player.server);
        if (onboarding.journey(player.getUUID(), settlement.id()).isEmpty()) {
            var quests = QuestSavedData.get(player.server);
            boolean legacy = quests.progress(player.getUUID(), settlement.id()).state() != QuestState.AVAILABLE
                    || quests.quests(player.getUUID(), settlement.id()).stream().anyMatch(quest ->
                    quest.template() == QuestTemplate.FIRST_MEETING ? quest.state() == QuestState.COMPLETED
                            : quest.state() == QuestState.ACTIVE || quest.state() == QuestState.COMPLETED);
            // Resource-only abstract records must leave the first mayor-backed chapter available.
            if (legacy && onboarding.enrollLegacy(player.getUUID(), settlement.id(), settlement.name())
                    && (quests.mainSettlement(player.getUUID()).filter(settlement.id()::equals).isPresent()
                        || quests.mayor(settlement.id()).isPresent()))
                quests.preserveLegacyChapter(player.getUUID(), settlement.id());
        }
        return onboarding.stage(player.getUUID(), settlement.id()) == OnboardingStage.JOINED;
    }

    public static boolean begin(ServerPlayer player, Settlement settlement, Entity mayor) {
        if (!validMayor(player, settlement, mayor)) return false;
        if (!canHelp(player, settlement)) {
            var data = KingdomOnboardingSavedData.get(player.server);
            if (data.journey(player.getUUID(), settlement.id()).isEmpty() && !data.begin(player.getUUID(), settlement.id())) return false;
        }
        else ExpandedQuestService.meetMayor(player, settlement, mayor);
        return true;
    }

    public static boolean advance(ServerPlayer player, Settlement settlement, Entity mayor, String action, String name) {
        if (!validMayor(player, settlement, mayor) || action == null) return false;
        var onboarding = KingdomOnboardingSavedData.get(player.server);
        if (onboarding.journey(player.getUUID(), settlement.id()).isEmpty()) return false;
        if (!action.equals("REGISTER")) return onboarding.advance(player.getUUID(), settlement.id(), action);
        if (onboarding.stage(player.getUUID(), settlement.id()) != OnboardingStage.NAMING
                || !onboarding.canRecord(player.getUUID(), settlement.id())) return false;
        var settlements = SettlementSavedData.get(player.server);
        Settlement current = settlements.get(settlement.id()).orElse(null);
        if (current == null) return false;
        boolean founding = onboarding.registration(settlement.id()).isEmpty();
        if (founding) {
            var normalized = KingdomName.validate(name);
            if (normalized.isEmpty() || !onboarding.nameAvailable(settlement.id(), normalized.get())) return false;
            if (!settlements.rename(current, normalized.get())) return false;
            name = normalized.get();
        }
        if (!onboarding.join(player.getUUID(), settlement.id(), name)) return false;
        Settlement registered = settlements.get(settlement.id()).orElseThrow();
        ExpandedQuestService.meetMayor(player, registered, mayor);
        player.displayClientMessage(Component.translatable(founding ? "onboarding.livingkingdoms.founded"
                : "onboarding.livingkingdoms.joined", registered.name()), false);
        return true;
    }

    public static void writeView(ServerPlayer player, Settlement settlement, CompoundTag view) {
        canHelp(player, settlement);
        var data = KingdomOnboardingSavedData.get(player.server);
        view.putString("onboarding_stage", data.stage(player.getUUID(), settlement.id()).name());
        view.putBoolean("kingdom_registered", data.registration(settlement.id()).isPresent());
        view.putInt("kingdom_name_limit", KingdomName.MAX_LENGTH);
        view.putBoolean("can_help", data.stage(player.getUUID(), settlement.id()) == OnboardingStage.JOINED);
    }

    private static boolean validMayor(ServerPlayer player, Settlement settlement, Entity mayor) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Mayor dialogue requires the server thread");
        if (!(mayor instanceof Villager) || mayor.level() != player.serverLevel() || !mayor.isAlive()
                || !player.isAlive() || player.isSpectator() || player.distanceToSqr(mayor) > 64
                || !settlement.faction().isAllied() || !settlement.territory().contains(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ())
                || SettlementSavedData.get(player.server).get(settlement.id()).filter(current -> current.faction().isAllied()
                    && current.territory().equals(settlement.territory())).isEmpty()) return false;
        return NpcIdentity.read(mayor).filter(id -> id.role() == NpcRole.MAYOR && id.settlementId().equals(settlement.id())).isPresent()
                && QuestSavedData.get(player.server).mayor(settlement.id()).filter(mayor.getUUID()::equals).isPresent();
    }
}

package dev.livingkingdoms.defense;

import dev.livingkingdoms.config.QuestExpansionConfig;
import dev.livingkingdoms.defense.domain.SettlementThreatEvent;
import dev.livingkingdoms.defense.domain.ThreatState;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Contextual offers use the existing per-player quest lifecycle; shared progress belongs to the local event. */
public final class DefenseQuestService {
    private DefenseQuestService() {}

    public static UUID identity(UUID event, UUID player) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(player, "player");
        return UUID.nameUUIDFromBytes((event + "/player/" + player).getBytes(StandardCharsets.UTF_8));
    }

    /** Pure content snapshot. The ordinary combat policy supplies modest configurable rewards. */
    public static QuestInstance offer(SettlementThreatEvent event, PartyType type, UUID player, QuestRules rules) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rules, "rules");
        if (!event.threatened() || type.faction() != event.faction())
            throw new IllegalArgumentException("A defense offer needs its active hostile party");
        LevelValue level = new LevelValue(event.recommendedLevel());
        QuestDifficulty difficulty = QuestDifficulty.fromLevel(level);
        QuestRewards rewards = event.rewardEligible()
                ? QuestRewardPolicy.forTemplate(QuestTemplate.LOCAL_DEFENSE, difficulty, rules)
                : new QuestRewards(0, 0);
        return new QuestInstance(identity(event.id(), player), QuestTemplate.LOCAL_DEFENSE,
                new QuestSource(event.settlementId(), null, QuestSourceRole.BOARD),
                new QuestObjective.Party(event.partyId(), event.faction(), type), level, difficulty, rewards,
                QuestState.AVAILABLE, false, event.detectedAt(), event.expiresAt());
    }

    /** Runs even during the ordinary board cooldown. No rotations manufacture or replace these offers. */
    public static void prepare(ServerPlayer player, Settlement settlement) {
        authority(player.server);
        QuestSavedData data = QuestSavedData.get(player.server);
        for (QuestInstance quest : data.quests(player.getUUID(), settlement.id())) {
            if (quest.template() != QuestTemplate.LOCAL_DEFENSE
                    || (quest.state() != QuestState.AVAILABLE && quest.state() != QuestState.ACTIVE)
                    || !(quest.objective() instanceof QuestObjective.Party target)) continue;
            var event = DefenseService.eventForParty(player.server, target.partyId());
            if (event.isEmpty()) data.failDefenseParty(target.partyId(), settlement.id());
            else if (event.get().state() == ThreatState.RESOLVED) resolved(player.server, event.get());
            else if (event.get().state() == ThreatState.FAILED) failed(player.server, event.get());
        }
        if (!settlement.faction().isAllied() || !settlement.territory().dimension()
                .equals(player.serverLevel().dimension().location().toString())) return;
        var event = DefenseService.current(player.server, settlement.id());
        if (event.isEmpty() || !event.get().threatened()
                || player.server.overworld().getGameTime() >= event.get().expiresAt()) return;
        var party = EncounterSavedData.get(player.server).get(event.get().partyId());
        if (party.isEmpty() || party.get().state() != PartyState.ALIVE
                || party.get().faction() != event.get().faction()
                || !party.get().origin().dimension().equals(event.get().dimension())) return;
        data.offer(player.getUUID(), offer(event.get(), party.get().type(), player.getUUID(), QuestExpansionConfig.rules()));
    }

    public static boolean canAccept(ServerPlayer player, QuestInstance quest) {
        authority(player.server);
        if (quest.template() != QuestTemplate.LOCAL_DEFENSE || quest.state() != QuestState.AVAILABLE
                || !(quest.objective() instanceof QuestObjective.Party target)) return false;
        var event = DefenseService.eventForParty(player.server, target.partyId());
        if (event.isEmpty() || !event.get().threatened() || !matches(quest, event.get(), player.getUUID())
                || player.server.overworld().getGameTime() >= event.get().expiresAt()) return false;
        return EncounterSavedData.get(player.server).get(target.partyId())
                .filter(party -> party.state() == PartyState.ALIVE && party.faction() == target.faction()
                        && party.type() == target.partyType()).isPresent();
    }

    /** Revalidate frozen event credit before the existing atomic quest/inventory receipt is consumed. */
    public static boolean canClaim(ServerPlayer player, QuestInstance quest) {
        authority(player.server);
        if (!(quest.objective() instanceof QuestObjective.Party target)) return false;
        return DefenseService.eventForParty(player.server, target.partyId())
                .filter(event -> canClaim(quest, event, player.getUUID())).isPresent();
    }

    public static boolean canClaim(QuestInstance quest, SettlementThreatEvent event, UUID player) {
        return quest.state() == QuestState.ACTIVE && quest.objectiveSatisfied()
                && matches(quest, event, player) && event.successfulParticipant(player);
    }

    public static boolean matches(QuestInstance quest, SettlementThreatEvent event, UUID player) {
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(event, "event");
        return quest.template() == QuestTemplate.LOCAL_DEFENSE && quest.id().equals(identity(event.id(), player))
                && quest.source().settlementId().equals(event.settlementId())
                && quest.objective() instanceof QuestObjective.Party target
                && target.partyId().equals(event.partyId()) && target.faction() == event.faction();
    }

    public static void resolved(MinecraftServer server, SettlementThreatEvent event) {
        authority(server);
        if (event.state() != ThreatState.RESOLVED) return;
        QuestSavedData.get(server).resolveDefenseParty(event.partyId(), event.settlementId(), event.participants());
    }

    public static void failed(MinecraftServer server, SettlementThreatEvent event) {
        authority(server);
        if (event.state() != ThreatState.FAILED) return;
        QuestSavedData.get(server).failDefenseParty(event.partyId(), event.settlementId());
    }

    private static void authority(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Defense quests require the server thread");
    }
}

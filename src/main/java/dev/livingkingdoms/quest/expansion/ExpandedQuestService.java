package dev.livingkingdoms.quest.expansion;

import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.QuestExpansionConfig;
import dev.livingkingdoms.encounter.domain.HostileParty;
import dev.livingkingdoms.encounter.domain.PartyState;
import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.encounter.persistence.EncounterSavedData;
import dev.livingkingdoms.progression.RegionalDifficultyService;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.QuestService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Board/Mayor orchestration only: generation is on demand, combat credit arrives through defeat events. */
public final class ExpandedQuestService {
    private ExpandedQuestService() {}

    public static void inspectBoard(ServerPlayer player, Settlement settlement, BlockPos board) {
        if (boardSettlement(player, board).filter(candidate -> candidate.id().equals(settlement.id())).isEmpty()) return;
        prepare(player, settlement);
        showBoard(player, settlement, board);
    }

    public static void meetMayor(ServerPlayer player, Settlement settlement, Entity mayor) {
        authority(player);
        if (player.isSpectator() || !player.isAlive() || player.distanceToSqr(mayor) > 64
                || !settlement.faction().isAllied() || !inTerritory(player, settlement)) return;
        var data = QuestSavedData.get(player.server);
        if (data.mayor(settlement.id()).filter(mayor.getUUID()::equals).isEmpty()) return;
        ensureMain(player, settlement);
        main(data, player.getUUID(), settlement.id(), QuestTemplate.FIRST_MEETING).ifPresent(quest -> {
            if (quest.state() == QuestState.AVAILABLE) data.acceptExpanded(player.getUUID(), quest.id());
            if (data.markObjective(player.getUUID(), quest.id())) data.completeExpanded(player.getUUID(), quest.id());
        });
        ensureMain(player, settlement);
    }

    /** Click commands carry a board position; changing UUID/coordinates cannot bypass ownership, reach or territory. */
    public static boolean act(ServerPlayer player, BlockPos board, UUID questId, String action) {
        return act(player, board, questId, action, true);
    }

    public static boolean act(ServerPlayer player, BlockPos board, UUID questId, String action, boolean chat) {
        var found = boardSettlement(player, board);
        if (found.isEmpty()) { message(player, "quest.livingkingdoms.no_settlement"); return false; }
        Settlement settlement = found.orElseThrow();
        prepare(player, settlement);
        var data = QuestSavedData.get(player.server);
        QuestInstance quest = data.quest(player.getUUID(), questId)
                .filter(candidate -> candidate.source().settlementId().equals(settlement.id())).orElse(null);
        if (quest == null) { message(player, "quest.livingkingdoms.request_unavailable"); return false; }
        if (action.equals("inspect")) { showQuest(player, quest, board); return true; }
        if (quest.template() == QuestTemplate.FIRST_MEETING) {
            message(player, "quest.livingkingdoms.meet_mayor"); return false;
        }
        if (quest.template() == QuestTemplate.MAIN_IRON) {
            // One existing delivery/reward remains the source of truth for the main chapter's iron step.
            if (!((action.equals("accept") && quest.state() == QuestState.AVAILABLE)
                    || (action.equals("claim") && quest.state() == QuestState.ACTIVE))) return false;
            if (quest.state() == QuestState.AVAILABLE && !data.acceptExpanded(player.getUUID(), quest.id())) return false;
            QuestService.interact(player, board, true, chat);
            ensureMain(player, settlement);
            QuestState resulting = data.progress(player.getUUID(), settlement.id()).state();
            return action.equals("accept") ? resulting == QuestState.ACTIVE : resulting == QuestState.COMPLETED;
        }
        boolean success;
        if (action.equals("accept")) {
            success = data.acceptExpanded(player.getUUID(), questId);
            if (success && quest.objective() instanceof QuestObjective.Return) data.markObjective(player.getUUID(), questId);
            if (success) message(player, "quest.livingkingdoms.accepted", Component.translatable(quest.template().titleKey()));
        } else if (action.equals("claim")) {
            success = claim(player, data, quest);
        } else return false;
        prepare(player, settlement);
        if (chat) showBoard(player, settlement, board);
        return success;
    }

    private static boolean claim(ServerPlayer player, QuestSavedData data, QuestInstance quest) {
        if (quest.state() != QuestState.ACTIVE) return false;
        List<ResourceRequirement> requirements = List.of();
        if (quest.objective() instanceof QuestObjective.Resource resource) {
            requirements = resource.requirements();
            if (requirements.stream().anyMatch(term -> DeliveryInventory.count(player.getInventory(), term.resource()) < term.count())) {
                message(player, "quest.livingkingdoms.resources_missing"); return false;
            }
        } else if (!quest.objectiveSatisfied()) {
            message(player, "quest.livingkingdoms.objective_pending"); return false;
        }
        var exchange = DeliveryInventory.plan(player.getInventory(), requirements, quest.rewards().emeralds());
        if (exchange.isEmpty()) { message(player, "quest.livingkingdoms.inventory_full"); return false; }
        // The full inventory exchange is preflighted before the one-time state/reputation transition.
        data.markObjective(player.getUUID(), quest.id());
        if (!data.completeExpanded(player.getUUID(), quest.id())) return false;
        exchange.orElseThrow().apply(player.getInventory());
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        message(player, "quest.livingkingdoms.request_claimed", Component.translatable(quest.template().titleKey()),
                quest.rewards().emeralds(), quest.rewards().reputation());
        return true;
    }

    public static void prepare(ServerPlayer player, Settlement settlement) {
        var data = QuestSavedData.get(player.server);
        long now = now(player);
        data.expireOffers(player.getUUID(), settlement.id(), now);
        var encounters = EncounterSavedData.get(player.server);
        for (QuestInstance quest : data.quests(player.getUUID(), settlement.id())) {
            if (quest.objective() instanceof QuestObjective.Party target && !quest.objectiveSatisfied()
                    && (quest.state() == QuestState.AVAILABLE || quest.state() == QuestState.ACTIVE)) {
                var party = encounters.get(target.partyId());
                if (party.isEmpty() || party.get().state() != PartyState.ALIVE || party.get().debug()
                        || party.get().faction() != target.faction() || party.get().type() != target.partyType()) {
                    data.failExpanded(player.getUUID(), quest.id());
                }
            }
        }
        ensureMain(player, settlement);
        var rules = QuestExpansionConfig.rules();
        long refreshed = data.boardRefreshAt(player.getUUID(), settlement.id());
        if (refreshed >= 0 && (now < refreshed || now - refreshed < rules.refreshTicks())) return;
        List<QuestInstance> current = data.quests(player.getUUID(), settlement.id());
        long active = current.stream().filter(quest -> quest.template().category() == QuestCategory.DYNAMIC && quest.state() == QuestState.ACTIVE).count();
        Set<UUID> alreadyTargeted = current.stream().filter(quest -> quest.template().category() == QuestCategory.DYNAMIC
                && quest.state() == QuestState.ACTIVE && quest.objective() instanceof QuestObjective.Party)
                .map(quest -> ((QuestObjective.Party) quest.objective()).partyId()).collect(Collectors.toSet());
        var targets = nearby(player, settlement).stream().filter(party -> !alreadyTargeted.contains(party.id()))
                .map(party -> new QuestGenerator.PartyTarget(party.id(), party.faction(), party.type(), party.levels())).toList();
        LevelValue regional = RegionalDifficultyService.at(player.serverLevel(), center(settlement)).level();
        long seed = player.getUUID().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits()
                ^ settlement.id().getMostSignificantBits() ^ Long.rotateLeft(settlement.id().getLeastSignificantBits(), 19)
                ^ data.boardGeneration(player.getUUID(), settlement.id());
        var offers = QuestGenerator.generate(settlement.id(), regional, targets, Map.of(), rules, new Random(seed), now);
        int slots = Math.max(0, rules.dynamicCount() - (int) active);
        data.rotateBoard(player.getUUID(), settlement.id(), now, rules.refreshTicks(), offers.stream().limit(slots).toList());
    }

    private static void ensureMain(ServerPlayer player, Settlement settlement) {
        var data = QuestSavedData.get(player.server);
        UUID owner = player.getUUID();
        // An abstract/debug settlement without a Mayor cannot strand the player's global chapter.
        if (data.mainSettlement(owner).isEmpty() && data.mayor(settlement.id()).isEmpty()) return;
        if (!data.anchorMain(owner, settlement.id())) return;
        var rules = QuestExpansionConfig.rules();
        for (int step = 0; step < QuestCatalog.mainChain().size(); step++) {
            Set<QuestTemplate> completed = data.quests(owner, settlement.id()).stream()
                    .filter(quest -> quest.state() == QuestState.COMPLETED && quest.template().category() == QuestCategory.MAIN)
                    .map(QuestInstance::template).collect(Collectors.toSet());
            var next = QuestCatalog.nextMain(completed);
            if (next.isEmpty()) return;
            QuestTemplate template = next.orElseThrow();
            QuestInstance existing = main(data, owner, settlement.id(), template).orElse(null);
            if (existing == null || (template == QuestTemplate.MAIN_PATROL && existing.state() == QuestState.FAILED)) {
                QuestObjective objective;
                LevelValue level = RegionalDifficultyService.at(player.serverLevel(), center(settlement)).level();
                switch (template) {
                    case FIRST_MEETING -> objective = new QuestObjective.Meet(QuestSourceRole.MAYOR);
                    case MAIN_IRON -> {
                        var legacy = data.progress(owner, settlement.id());
                        int amount = legacy.terms() == null ? QuestService.currentTerms().requiredIron() : legacy.terms().requiredIron();
                        objective = new QuestObjective.Resource(List.of(new ResourceRequirement(ResourceKind.IRON_INGOT, amount)));
                    }
                    case MAIN_PATROL -> {
                        var target = nearby(player, settlement).stream().filter(party -> party.type() == PartyType.PILLAGER_PATROL)
                                .min(Comparator.comparingDouble((HostileParty party) -> distanceSquared(party, settlement)).thenComparing(HostileParty::id));
                        if (target.isEmpty()) return;
                        HostileParty party = target.orElseThrow();
                        objective = new QuestObjective.Party(party.id(), party.faction(), party.type());
                        level = new LevelValue((int) Math.ceil(party.levels().average()));
                    }
                    case MAIN_RETURN -> objective = new QuestObjective.Return();
                    default -> throw new IllegalStateException("Unexpected main template");
                }
                QuestDifficulty difficulty = QuestDifficulty.fromLevel(level);
                UUID npc = template.sourceRole() == QuestSourceRole.MAYOR ? data.mayor(settlement.id()).orElse(null) : null;
                var offer = new QuestInstance(UUID.randomUUID(), template, new QuestSource(settlement.id(), npc, template.sourceRole()),
                        objective, level, difficulty, QuestRewardPolicy.forTemplate(template, difficulty, rules), QuestState.AVAILABLE, false, now(player), -1);
                if (!data.offer(owner, offer)) return;
                existing = offer;
            }
            if (template == QuestTemplate.MAIN_IRON) {
                var legacy = data.progress(owner, settlement.id());
                if (legacy.state() == QuestState.ACTIVE || legacy.state() == QuestState.COMPLETED) data.acceptExpanded(owner, existing.id());
                if (legacy.state() == QuestState.COMPLETED) {
                    data.markObjective(owner, existing.id());
                    data.completeExpanded(owner, existing.id());
                    continue;
                }
            }
            return;
        }
    }

    private static Optional<QuestInstance> main(QuestSavedData data, UUID player, UUID settlement, QuestTemplate template) {
        return data.quests(player, settlement).stream().filter(quest -> quest.template() == template).findFirst();
    }

    private static List<HostileParty> nearby(ServerPlayer player, Settlement settlement) {
        BlockPos center = center(settlement);
        return EncounterSavedData.get(player.server).activeNearby(settlement.territory().dimension(), center.getX(), center.getZ(),
                QuestExpansionConfig.rules().encounterRange()).stream().filter(party -> !party.debug()).sorted(Comparator.comparing(HostileParty::id)).toList();
    }

    private static double distanceSquared(HostileParty party, Settlement settlement) {
        double dx = (double) party.origin().x() - settlement.territory().x();
        double dz = (double) party.origin().z() - settlement.territory().z();
        return dx * dx + dz * dz;
    }

    private static void showBoard(ServerPlayer player, Settlement settlement, BlockPos board) {
        var data = QuestSavedData.get(player.server);
        message(player, "quest.livingkingdoms.main_heading");
        var anchor = data.mainSettlement(player.getUUID());
        if (anchor.filter(settlement.id()::equals).isPresent()) {
            var mains = data.quests(player.getUUID(), settlement.id()).stream().filter(quest -> quest.template().category() == QuestCategory.MAIN)
                    .sorted(Comparator.comparingInt(quest -> quest.template().order())).toList();
            mains.forEach(quest -> showQuest(player, quest, board));
            if (mains.stream().anyMatch(quest -> quest.template() == QuestTemplate.MAIN_IRON && quest.state() == QuestState.COMPLETED)
                    && mains.stream().noneMatch(quest -> quest.template() == QuestTemplate.MAIN_PATROL && quest.state() != QuestState.FAILED)) {
                message(player, "quest.livingkingdoms.waiting_patrol");
            }
        } else if (anchor.isEmpty()) message(player, "quest.livingkingdoms.meet_mayor");
        else {
            Component name = SettlementSavedData.get(player.server).get(anchor.orElseThrow())
                    .map(settlementName -> (Component) Component.literal(dev.livingkingdoms.ui.VillageNames.display(settlementName)))
                    .orElse(Component.translatable("ui.livingkingdoms.original_settlement"));
            message(player, "quest.livingkingdoms.main_other_settlement", name);
        }
        message(player, "quest.livingkingdoms.requests_heading");
        data.quests(player.getUUID(), settlement.id()).stream().filter(quest -> quest.template().category() == QuestCategory.DYNAMIC)
                .forEach(quest -> showQuest(player, quest, board));
        message(player, "quest.livingkingdoms.rotation_hint", QuestExpansionConfig.rules().refreshTicks() / 24000.0);
    }

    private static void showQuest(ServerPlayer player, QuestInstance quest, BlockPos board) {
        message(player, "quest.livingkingdoms.request_header", Component.translatable(quest.template().titleKey()),
                Component.translatable("quest.livingkingdoms.state." + quest.state().name().toLowerCase(java.util.Locale.ROOT)),
                quest.recommendedLevel().value(), Component.translatable(quest.difficulty().titleKey()));
        message(player, "quest.livingkingdoms.request_source", Component.translatable("quest.livingkingdoms.source." + quest.source().role().name().toLowerCase(java.util.Locale.ROOT)));
        if (quest.state() == QuestState.COMPLETED || quest.state() == QuestState.FAILED || quest.state() == QuestState.EXPIRED) return;
        switch (quest.objective()) {
            case QuestObjective.Resource resource -> {
                for (ResourceRequirement term : resource.requirements()) {
                    int required = term.count();
                    if (quest.template() == QuestTemplate.MAIN_IRON) {
                        var legacy = QuestSavedData.get(player.server).progress(player.getUUID(), quest.source().settlementId());
                        required = legacy.terms() == null ? QuestService.currentTerms().requiredIron() : legacy.terms().requiredIron();
                    }
                    message(player, "quest.livingkingdoms.resource_objective", required,
                            Component.translatable("quest.livingkingdoms.resource." + term.resource().id()),
                            Math.min(required, DeliveryInventory.count(player.getInventory(), term.resource())));
                }
            }
            case QuestObjective.Party party -> {
                message(player, quest.objectiveSatisfied() ? "quest.livingkingdoms.party_ready" : "quest.livingkingdoms.party_objective",
                        Component.translatable("encounter.livingkingdoms.type." + party.partyType().id()));
                if (!quest.objectiveSatisfied()) EncounterSavedData.get(player.server).get(party.partyId()).ifPresent(target ->
                        message(player, "quest.livingkingdoms.party_location", target.origin().x(), target.origin().z()));
            }
            case QuestObjective.Meet ignored -> message(player, "quest.livingkingdoms.meet_mayor");
            case QuestObjective.Return ignored -> message(player, "quest.livingkingdoms.return_objective");
        }
        if (quest.template() == QuestTemplate.MAIN_IRON) {
            var progress = QuestSavedData.get(player.server).progress(player.getUUID(), quest.source().settlementId());
            var terms = progress.terms() == null ? QuestService.currentTerms() : progress.terms();
            message(player, "quest.livingkingdoms.rewards", terms.rewardEmeralds(), terms.reputationReward());
            message(player, "quest.livingkingdoms.main_iron_hint");
        } else message(player, "quest.livingkingdoms.rewards", quest.rewards().emeralds(), quest.rewards().reputation());
        if (quest.objective() instanceof QuestObjective.Meet) return;
        String action = quest.state() == QuestState.AVAILABLE ? "accept" : "claim";
        if (quest.state() == QuestState.ACTIVE && quest.objective() instanceof QuestObjective.Party && !quest.objectiveSatisfied()) return;
        String command = "/kingdom quest " + action + " " + quest.id() + " " + board.getX() + " " + board.getY() + " " + board.getZ();
        player.displayClientMessage(Component.translatable("quest.livingkingdoms.action." + action)
                .withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))), false);
    }

    public static Optional<Settlement> boardSettlement(ServerPlayer player, BlockPos board) {
        authority(player);
        var level = player.serverLevel();
        if (player.isSpectator() || !player.isAlive() || player.distanceToSqr(Vec3.atCenterOf(board)) > 64
                || !level.getChunkSource().hasChunk(board.getX() >> 4, board.getZ() >> 4)
                || !level.getBlockState(board).is(KingdomBlocks.QUEST_BOARD)) return Optional.empty();
        return SettlementSavedData.get(player.server).at(level.dimension().location().toString(), board.getX(), board.getZ())
                .filter(settlement -> settlement.faction().isAllied() && inTerritory(player, settlement));
    }

    private static boolean inTerritory(ServerPlayer player, Settlement settlement) {
        BlockPos at = player.blockPosition();
        return settlement.territory().contains(player.serverLevel().dimension().location().toString(), at.getX(), at.getZ());
    }
    private static BlockPos center(Settlement settlement) {
        var territory = settlement.territory(); return new BlockPos(territory.x(), territory.y(), territory.z());
    }
    private static long now(ServerPlayer player) { return Math.max(0, player.server.overworld().getGameTime()); }
    private static void authority(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Quest interaction requires the server thread");
    }
    private static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), false);
    }
}

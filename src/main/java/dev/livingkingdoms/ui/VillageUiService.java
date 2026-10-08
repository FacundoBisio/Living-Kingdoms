package dev.livingkingdoms.ui;

import dev.livingkingdoms.npc.*;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.QuestService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** On-open snapshots and short-lived, player-bound capabilities. No global/tick scanning. */
public final class VillageUiService {
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    private VillageUiService() {}
    private record Session(UUID token, UUID settlement, String dimension, BlockPos board, UUID npc, long opened,
                           BlockPos marker, boolean construction) {
        Session(UUID token, UUID settlement, String dimension, BlockPos board, UUID npc, long opened) {
            this(token,settlement,dimension,board,npc,opened,null,false);
        }
        Session constructionView() { return new Session(token,settlement,dimension,board,npc,opened,marker,true); }
    }

    public static boolean openConstructionMarker(ServerPlayer player,BlockPos marker) {
        if(player.isSpectator() || !player.isAlive() || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(marker))>64
                || !player.serverLevel().getChunkSource().hasChunk(marker.getX()>>4,marker.getZ()>>4)
                || !player.serverLevel().getBlockState(marker).is(dev.livingkingdoms.block.KingdomBlocks.CONSTRUCTION_MARKER)) return false;
        var storage=dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server);
        var project=storage.marker(player.level().dimension().location().toString(),marker).flatMap(storage::get);
        if(project.isEmpty()) return false;
        var settlement=SettlementSavedData.get(player.server).get(project.get().project().settlementId()).orElse(null);
        if(settlement==null || !settlement.faction().isAllied() || !inside(player,settlement)) return false;
        Session session=new Session(UUID.randomUUID(),settlement.id(),player.level().dimension().location().toString(),null,null,now(player),marker.immutable(),true);
        SESSIONS.put(player,session); sendConstruction(player,session,settlement,false); return true;
    }

    public static boolean openBoard(ServerPlayer player, BlockPos board) {
        var found = ExpandedQuestService.boardSettlement(player, board);
        if (found.isEmpty()) return false;
        var settlement = found.get();
        // Safe, lazy migration for old physical settlements lacking a Mayor association.
        BlockPos marker = new BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        if (QuestSavedData.get(player.server).mayor(settlement.id()).isEmpty()
                && player.serverLevel().hasChunkAt(marker) && player.serverLevel().getBlockState(marker).is(net.minecraft.world.level.block.Blocks.LODESTONE))
            NpcService.ensureMayor(player.serverLevel(), settlement);
        Session session = new Session(UUID.randomUUID(), settlement.id(), player.level().dimension().location().toString(), board.immutable(), null, now(player));
        SESSIONS.put(player, session);
        sendBoard(player, session, settlement);
        return true;
    }

    public static boolean openDialogue(ServerPlayer player, Villager npc) {
        var identity = NpcIdentity.read(npc).filter(id -> id.role() == NpcRole.MAYOR);
        if (identity.isEmpty() || !npc.isAlive() || player.distanceToSqr(npc) > 64 || player.isSpectator() || !player.isAlive()) return false;
        var settlement = SettlementSavedData.get(player.server).get(identity.get().settlementId());
        if (settlement.isEmpty() || !settlement.get().faction().isAllied() || !inside(player, settlement.get())
                || QuestSavedData.get(player.server).mayor(settlement.get().id()).filter(npc.getUUID()::equals).isEmpty()) return false;
        MayorPresentation.apply(npc);
        ExpandedQuestService.meetMayor(player, settlement.get(), npc);
        Session session = new Session(UUID.randomUUID(), settlement.get().id(), player.level().dimension().location().toString(), null, npc.getUUID(), now(player));
        SESSIONS.put(player, session);
        sendDialogue(player, session, settlement.get(), npc, false);
        return true;
    }

    public static boolean handle(ServerPlayer player, UiPayloads.Request request) {
        if (!player.server.isSameThread()) throw new IllegalStateException("UI actions require server thread");
        Session session = SESSIONS.get(player);
        if (session == null || !session.token.equals(request.session())) return false;
        if (request.action() == UiPayloads.Action.CLOSE) { SESSIONS.remove(player); return true; }
        if (!player.isAlive() || player.isSpectator() || now(player) - session.opened > 12000
                || !session.dimension.equals(player.level().dimension().location().toString())) return invalidate(player);
        var settlement = SettlementSavedData.get(player.server).get(session.settlement).orElse(null);
        if (settlement == null || !settlement.faction().isAllied() || !inside(player, settlement)) return invalidate(player);
        if (session.marker != null && (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.marker)) > 64
                || !player.serverLevel().getChunkSource().hasChunk(session.marker.getX()>>4,session.marker.getZ()>>4)
                || !player.serverLevel().getBlockState(session.marker).is(dev.livingkingdoms.block.KingdomBlocks.CONSTRUCTION_MARKER)
                || dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server)
                    .marker(session.dimension,session.marker).flatMap(id -> dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).get(id))
                    .filter(e -> e.project().settlementId().equals(session.settlement)).isEmpty())) return invalidate(player);
        // A construction view retains its original physical anchor and all reach/ownership checks.
        if (session.npc != null && !validMayor(player,session)) return invalidate(player);
        if (session.board != null && ExpandedQuestService.boardSettlement(player,session.board).filter(s -> s.id().equals(session.settlement)).isEmpty()) return invalidate(player);
        if (request.action() == UiPayloads.Action.CONSTRUCTION || session.construction) {
            Session construction=session.constructionView(); SESSIONS.put(player,construction);
            boolean result;
            try { result=switch(request.action()) {
                case CONSTRUCTION, REFRESH -> true;
                case DEPOSIT -> dev.livingkingdoms.construction.ConstructionService.deposit(player,settlement.id(),request.quest());
                case RETRY -> dev.livingkingdoms.construction.ConstructionService.retry(player,settlement.id(),request.quest());
                case PLAN -> dev.livingkingdoms.construction.ConstructionService.ensureNext(player.serverLevel(),settlement.id(),player);
                default -> false;
            }; } catch (RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("Construction interaction rejected for {}: {}",settlement.id(),failure.toString());
                result=false;
            }
            sendConstruction(player,construction,SettlementSavedData.get(player.server).get(settlement.id()).orElseThrow(),!result);
            return result;
        }
        if (session.board != null) {
            if (ExpandedQuestService.boardSettlement(player, session.board).filter(s -> s.id().equals(session.settlement)).isEmpty()) return invalidate(player);
            boolean result = switch (request.action()) {
                case ACCEPT, CLAIM -> ExpandedQuestService.act(player, session.board, request.quest(), request.action() == UiPayloads.Action.ACCEPT ? "accept" : "claim", false);
                case REFRESH -> true;
                default -> false;
            };
            sendBoard(player, session, settlement, !result);
            return result;
        }
        var entity = player.serverLevel().getEntity(session.npc);
        if (!(entity instanceof Villager npc) || !npc.isAlive() || player.distanceToSqr(npc) > 64
                || NpcIdentity.read(npc).filter(id -> id.role() == NpcRole.MAYOR && id.settlementId().equals(session.settlement)).isEmpty()
                || QuestSavedData.get(player.server).mayor(session.settlement).filter(npc.getUUID()::equals).isEmpty()) return invalidate(player);
        if (request.action() == UiPayloads.Action.BOARD) {
            // Bounded local search also supports legacy 31x31 settlements with different board offsets.
            for (BlockPos board : BlockPos.betweenClosed(player.blockPosition().offset(-8,-3,-8), player.blockPosition().offset(8,3,8))) {
                if (ExpandedQuestService.boardSettlement(player, board).filter(s -> s.id().equals(session.settlement)).isPresent()) return openBoard(player, board);
            }
            player.displayClientMessage(Component.translatable("ui.livingkingdoms.board_out_of_reach"), true);
            sendDialogue(player, session, settlement, npc, false);
            return false;
        }
        if (request.action() != UiPayloads.Action.TALK && request.action() != UiPayloads.Action.INFO && request.action() != UiPayloads.Action.REFRESH) return false;
        sendDialogue(player, session, settlement, npc, request.action() == UiPayloads.Action.INFO);
        return true;
    }

    private static boolean invalidate(ServerPlayer player) {
        SESSIONS.remove(player);
        CompoundTag closed = new CompoundTag(); closed.putString("screen", "closed");
        PacketDistributor.sendToPlayer(player, new UiPayloads.Snapshot(closed));
        return false;
    }

    private static boolean validMayor(ServerPlayer player,Session session) {
        var entity=player.serverLevel().getEntity(session.npc);
        return entity instanceof Villager npc && npc.isAlive() && player.distanceToSqr(npc)<=64
                && NpcIdentity.read(npc).filter(id -> id.role()==NpcRole.MAYOR && id.settlementId().equals(session.settlement)).isPresent()
                && QuestSavedData.get(player.server).mayor(session.settlement).filter(npc.getUUID()::equals).isPresent();
    }

    private static CompoundTag base(ServerPlayer player, Session session, Settlement settlement, String screen) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("session", session.token); tag.putString("screen", screen);
        tag.putString("settlement", VillageNames.display(settlement));
        tag.putInt("reputation", QuestSavedData.get(player.server).reputation(player.getUUID(), settlement.id()));
        tag.putString("lifecycle",settlement.lifecycle().name());
        tag.putBoolean("construction",settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.FOUNDING
                || !dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).projects(settlement.id()).isEmpty());
        return tag;
    }

    private static void sendDialogue(ServerPlayer player, Session session, Settlement settlement, Villager npc, boolean info) {
        CompoundTag tag = base(player, session, settlement, "dialogue");
        tag.putInt("entity", npc.getId());
        tag.putString("name", MayorPresentation.name(npc));
        tag.putString("role", "mayor");
        tag.putString("dialogue", settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.FOUNDING ? "ui.livingkingdoms.dialogue.founding"
                : info ? "ui.livingkingdoms.dialogue.info" : QuestSavedData.get(player.server).progress(player.getUUID(), settlement.id()).state() == QuestState.COMPLETED
                ? "npc.livingkingdoms.mayor.after" : "npc.livingkingdoms.mayor.before");
        tag.putInt("level", settlement.level());
        PacketDistributor.sendToPlayer(player, new UiPayloads.Snapshot(tag));
    }

    private static void sendConstruction(ServerPlayer player,Session session,Settlement settlement,boolean rejected) {
        CompoundTag tag=base(player,session,settlement,"construction");
        if(rejected) tag.putString("notice","construction.livingkingdoms.rejected");
        ListTag projects=new ListTag();
        var entries=dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).projects(settlement.id());
        for(var entry:entries) {
            var p=entry.project(); CompoundTag view=new CompoundTag(); view.putUUID("id",p.id());
            view.putString("building",p.building().name().toLowerCase(java.util.Locale.ROOT)); view.putString("state",p.state().name());
            view.putInt("x",p.plot().x()); view.putInt("y",p.plot().y()); view.putInt("z",p.plot().z());
            view.putDouble("progress",p.progress(now(player))); view.putBoolean("awaiting_chunks",p.awaitingChunks());
            view.putLong("duration",p.durationTicks());
            view.putBoolean("waiting_site",dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).waitingForClearSite(p.id()));
            view.putLong("remaining",p.startedAt()<0 ? p.durationTicks()/20 : Math.max(0,(p.deadline()-now(player)+19)/20));
            ListTag costs=new ListTag();
            for(var kind:ResourceKind.values()) if(p.required().containsKey(kind)) {
                CompoundTag cost=new CompoundTag(); cost.putString("item",kind.id()); cost.putInt("required",p.required().get(kind));
                cost.putInt("count",p.supplied().getOrDefault(kind,0)); cost.putInt("carried",DeliveryInventory.count(player.getInventory(),kind)); costs.add(cost);
            }
            view.put("requirements",costs); projects.add(view);
        }
        tag.put("projects",projects); tag.putBoolean("plan",settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.FOUNDING
                && entries.stream().noneMatch(e -> e.project().state()!=dev.livingkingdoms.construction.domain.ConstructionState.COMPLETED));
        PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(tag));
    }

    private static void sendBoard(ServerPlayer player, Session session, Settlement settlement) {
        sendBoard(player, session, settlement, false);
    }

    private static void sendBoard(ServerPlayer player, Session session, Settlement settlement, boolean rejected) {
        ExpandedQuestService.prepare(player, settlement);
        CompoundTag tag = base(player, session, settlement, "board");
        if (rejected) tag.putString("notice", "ui.livingkingdoms.action_rejected");
        ListTag entries = new ListTag();
        QuestSavedData data = QuestSavedData.get(player.server);
        for (QuestInstance quest : data.quests(player.getUUID(), settlement.id())) entries.add(questView(player, quest));
        tag.put("quests", entries);
        var anchor = data.mainSettlement(player.getUUID());
        tag.putString("hint", anchor.filter(settlement.id()::equals).isPresent() ? "quest.livingkingdoms.waiting_patrol" : "quest.livingkingdoms.meet_mayor");
        if (anchor.isPresent() && !anchor.get().equals(settlement.id())) {
            tag.putString("hint", "ui.livingkingdoms.main_elsewhere");
        }
        PacketDistributor.sendToPlayer(player, new UiPayloads.Snapshot(tag));
    }

    public static CompoundTag questView(ServerPlayer player, QuestInstance quest) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", quest.id()); tag.putString("template", quest.template().id());
        tag.putString("category", quest.template().category().name()); tag.putString("state", quest.state().name());
        tag.putString("difficulty", quest.difficulty().titleKey()); tag.putInt("level", quest.recommendedLevel().value());
        tag.putLong("expires", quest.state() == QuestState.AVAILABLE && quest.expiresAt() >= 0 ? Math.max(0, (quest.expiresAt()-now(player))/20) : -1);
        int emeralds = quest.rewards().emeralds(), reputation = quest.rewards().reputation();
        var progress = QuestSavedData.get(player.server).progress(player.getUUID(), quest.source().settlementId());
        var terms = progress.terms() == null ? QuestService.currentTerms() : progress.terms();
        if (quest.template() == QuestTemplate.MAIN_IRON) { emeralds = terms.rewardEmeralds(); reputation = terms.reputationReward(); }
        tag.putInt("emeralds", emeralds); tag.putInt("reward_reputation", reputation);
        boolean ready = quest.objectiveSatisfied();
        ListTag requirements = new ListTag();
        switch (quest.objective()) {
            case QuestObjective.Resource resource -> {
                ready = true;
                for (var term : resource.requirements()) {
                    int required = quest.template() == QuestTemplate.MAIN_IRON ? terms.requiredIron() : term.count();
                    int count = quest.state() == QuestState.COMPLETED ? required : Math.min(required, DeliveryInventory.count(player.getInventory(), term.resource()));
                    CompoundTag item = new CompoundTag(); item.putString("item", term.resource().id());
                    item.putInt("required", required); item.putInt("count", count); requirements.add(item);
                    ready &= count >= required;
                }
                tag.putString("objective", "ui.livingkingdoms.deliver");
            }
            case QuestObjective.Party party -> {
                tag.putString("objective", ready ? "quest.livingkingdoms.party_ready" : "quest.livingkingdoms.party_objective");
                tag.putString("target", "encounter.livingkingdoms.type." + party.partyType().id());
                dev.livingkingdoms.encounter.persistence.EncounterSavedData.get(player.server).get(party.partyId()).ifPresent(target -> {
                    tag.putInt("target_x", target.origin().x()); tag.putInt("target_z", target.origin().z());
                });
            }
            case QuestObjective.Meet ignored -> tag.putString("objective", "quest.livingkingdoms.meet_mayor");
            case QuestObjective.Return ignored -> tag.putString("objective", "quest.livingkingdoms.return_objective");
        }
        tag.put("requirements", requirements); tag.putBoolean("ready", ready);
        tag.putBoolean("accept", quest.state() == QuestState.AVAILABLE && !(quest.objective() instanceof QuestObjective.Meet));
        tag.putBoolean("claim", quest.state() == QuestState.ACTIVE && ready);
        return tag;
    }
    private static boolean inside(ServerPlayer player, Settlement settlement) {
        var pos = player.blockPosition();
        return settlement.territory().contains(player.level().dimension().location().toString(), pos.getX(), pos.getZ());
    }

    private static long now(ServerPlayer player) { return player.server.overworld().getGameTime(); }
}

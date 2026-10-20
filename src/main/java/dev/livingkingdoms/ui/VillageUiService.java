package dev.livingkingdoms.ui;

import dev.livingkingdoms.npc.*;
import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.ImmigrationService;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.config.CitizenConfig;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.QuestService;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.ExpandedQuestService;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
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
    private static final Map<ServerPlayer,Map<UUID,Long>> CITIZEN_VIEWS=new WeakHashMap<>();
    private static final java.util.Set<ServerPlayer> HANDLING=java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private VillageUiService() {}
    private record Session(UUID token, UUID settlement, String dimension, BlockPos board, UUID npc, long opened,
                           BlockPos marker, boolean construction, boolean immigration, boolean citizens,boolean shortcut,boolean stock) {
        Session(UUID token,UUID settlement,String dimension,BlockPos board,UUID npc,long opened,BlockPos marker,boolean construction,boolean immigration,boolean citizens,boolean shortcut) {
            this(token,settlement,dimension,board,npc,opened,marker,construction,immigration,citizens,shortcut,false);
        }
        Session(UUID token,UUID settlement,String dimension,BlockPos board,UUID npc,long opened,BlockPos marker,boolean construction,boolean immigration,boolean citizens) {
            this(token,settlement,dimension,board,npc,opened,marker,construction,immigration,citizens,false);
        }
        Session(UUID token, UUID settlement, String dimension, BlockPos board, UUID npc, long opened) {
            this(token,settlement,dimension,board,npc,opened,null,false,false,false);
        }
        Session constructionView() { return new Session(token,settlement,dimension,board,npc,opened,marker,true,false,false,shortcut); }
        Session immigrationView() { return new Session(token,settlement,dimension,board,npc,opened,marker,false,true,false,shortcut); }
        Session citizensView() { return new Session(token,settlement,dimension,board,npc,opened,marker,false,false,true,shortcut); }
        Session originalView() { return new Session(token,settlement,dimension,board,npc,opened,marker,false,false,false,shortcut); }
        Session stockView() {return new Session(token,settlement,dimension,board,npc,opened,marker,false,false,false,shortcut,true);}
    }

    public static boolean openShortcut(ServerPlayer player) {
        if(!player.server.isSameThread() || !player.isAlive() || player.isSpectator()) return false;
        var pos=player.blockPosition();
        var settlement=SettlementSavedData.get(player.server).at(player.level().dimension().location().toString(),pos.getX(),pos.getZ())
                .filter(s -> s.faction().isAllied()).or(() -> dev.livingkingdoms.settlement.NearestAlliedSettlementService.findNearest(player.serverLevel(),pos,1040)
                        .filter(s -> Math.hypot(pos.getX()-s.territory().x(),pos.getZ()-s.territory().z())<=s.territory().radius()+16));
        if(settlement.isEmpty()) { player.displayClientMessage(Component.translatable("ui.livingkingdoms.shortcut.no_settlement"),true); return false; }
        if(dev.livingkingdoms.npckingdom.NpcKingdoms.refuseVisitor(player,settlement.get())) return false;
        var session=new Session(UUID.randomUUID(),settlement.get().id(),player.level().dimension().location().toString(),null,null,now(player),null,false,false,false,true);
        QuestJournalService.dismiss(player);SESSIONS.put(player,session); sendDialogue(player,session,settlement.get(),null,true); return true;
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
        Session session=new Session(UUID.randomUUID(),settlement.id(),player.level().dimension().location().toString(),null,null,now(player),marker.immutable(),true,false,false);
        QuestJournalService.dismiss(player);SESSIONS.put(player,session); sendConstruction(player,session,settlement,false); return true;
    }

    public static boolean openBoard(ServerPlayer player, BlockPos board) {
        var found = ExpandedQuestService.boardSettlement(player, board);
        if (found.isEmpty()) return false;
        var settlement = found.get();
        if (dev.livingkingdoms.npckingdom.NpcKingdoms.refuseVisitor(player, settlement)) return false;
        // Safe, lazy migration for old physical settlements lacking a Mayor association.
        BlockPos marker = new BlockPos(settlement.territory().x(), settlement.territory().y(), settlement.territory().z());
        if (QuestSavedData.get(player.server).mayor(settlement.id()).isEmpty()
                && player.serverLevel().hasChunkAt(marker) && player.serverLevel().getBlockState(marker).is(net.minecraft.world.level.block.Blocks.LODESTONE))
            NpcService.ensureMayor(player.serverLevel(), settlement);
        Session session = new Session(UUID.randomUUID(), settlement.id(), player.level().dimension().location().toString(), board.immutable(), null, now(player));
        QuestJournalService.dismiss(player);SESSIONS.put(player, session);
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
        // Independent NPC kingdoms greet players as visitors: no onboarding, quests or management.
        if (dev.livingkingdoms.npckingdom.NpcKingdoms.refuseVisitor(player, settlement.get())) return false;
        if (!KingdomOnboardingService.begin(player, settlement.get(), npc)) return false;
        Session session = new Session(UUID.randomUUID(), settlement.get().id(), player.level().dimension().location().toString(), null, npc.getUUID(), now(player));
        QuestJournalService.dismiss(player);SESSIONS.put(player, session);
        sendDialogue(player, session, settlement.get(), npc, false);
        return true;
    }

    /** The normal mayor capability also anchors the narrative/name transaction. */
    public static boolean handleMayor(ServerPlayer player, UiPayloads.MayorRequest request) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Mayor actions require server thread");
        Session session = SESSIONS.get(player);
        if (session == null || !session.token.equals(request.session()) || session.npc == null
                || session.shortcut || session.marker != null || session.board != null) return false;
        var settlement = SettlementSavedData.get(player.server).get(session.settlement).orElse(null);
        if (settlement == null || !player.isAlive() || player.isSpectator() || now(player) - session.opened > 12000
                || !session.dimension.equals(player.level().dimension().location().toString())
                || !inside(player, settlement) || !validMayor(player, session)) return invalidate(player);
        var mayor = (Villager) player.serverLevel().getEntity(session.npc);
        boolean result = KingdomOnboardingService.advance(player, settlement, mayor, request.action(), request.name());
        var current = SettlementSavedData.get(player.server).get(session.settlement).orElseThrow();
        sendDialogue(player, session, current, mayor, false, !result);
        return result;
    }

    public static boolean handle(ServerPlayer player, UiPayloads.Request request) {
        if (!player.server.isSameThread()) throw new IllegalStateException("UI actions require server thread");
        boolean first=HANDLING.add(player);
        try {return handleRequest(player,request);} finally {if(first) HANDLING.remove(player);}
    }
    public static void dismiss(ServerPlayer player) {SESSIONS.remove(player);CITIZEN_VIEWS.remove(player);}
    public static void refreshQuestViews(ServerPlayer player,UUID settlement) {
        if(HANDLING.contains(player) || !player.isAlive() || player.isSpectator()) return;
        if(QuestJournalService.refreshIfOpen(player)) return;
        var session=SESSIONS.get(player);
        if(session==null || session.board==null || session.construction || session.citizens || session.immigration || session.stock
                || !session.settlement.equals(settlement) || now(player)-session.opened>12000
                || !session.dimension.equals(player.level().dimension().location().toString())) return;
        ExpandedQuestService.boardSettlement(player,session.board).filter(s->s.id().equals(settlement)).ifPresent(s->sendBoard(player,session,s));
    }
    private static boolean handleRequest(ServerPlayer player, UiPayloads.Request request) {
        if(QuestJournalService.owns(player,request.session())) return QuestJournalService.handle(player,request);
        Session session = SESSIONS.get(player);
        if (session == null || !session.token.equals(request.session())) return false;
        if (request.action() == UiPayloads.Action.CLOSE) { dismiss(player); return true; }
        if (!player.isAlive() || player.isSpectator() || now(player) - session.opened > 12000
                || !session.dimension.equals(player.level().dimension().location().toString())) return invalidate(player);
        var settlement = SettlementSavedData.get(player.server).get(session.settlement).orElse(null);
        if (settlement == null || !settlement.faction().isAllied()) return invalidate(player);
        if(!inside(player,settlement)) {
            if(!session.shortcut || Math.hypot(player.getX()-settlement.territory().x(),player.getZ()-settlement.territory().z())>settlement.territory().radius()+16) return invalidate(player);
            if(request.action()!=UiPayloads.Action.INFO && request.action()!=UiPayloads.Action.TALK && request.action()!=UiPayloads.Action.REFRESH) return false;
            sendDialogue(player,session,settlement,null,true); return true;
        }
        if (session.marker != null && (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.marker)) > 64
                || !player.serverLevel().getChunkSource().hasChunk(session.marker.getX()>>4,session.marker.getZ()>>4)
                || !player.serverLevel().getBlockState(session.marker).is(dev.livingkingdoms.block.KingdomBlocks.CONSTRUCTION_MARKER)
                || dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server)
                    .marker(session.dimension,session.marker).flatMap(id -> dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).get(id))
                    .filter(e -> e.project().settlementId().equals(session.settlement)).isEmpty())) return invalidate(player);
        // A construction view retains its original physical anchor and all reach/ownership checks.
        if (session.npc != null && !validMayor(player,session)) return invalidate(player);
        if (session.board != null && ExpandedQuestService.boardSettlement(player,session.board).filter(s -> s.id().equals(session.settlement)).isEmpty()) return invalidate(player);
        if (!KingdomOnboardingService.canHelp(player, settlement) && switch (request.action()) {
            case INFO, TALK, REFRESH, BOARD, TRACK, UNTRACK -> false;
            default -> true;
        }) {
            player.displayClientMessage(Component.translatable("onboarding.livingkingdoms.required"), true);
            if (session.npc != null) sendDialogue(player, session, settlement, (Villager)player.serverLevel().getEntity(session.npc), false);
            else if (session.board != null) sendBoard(player, session, settlement, true);
            else if (session.marker != null || session.construction) sendConstruction(player, session, settlement, true);
            return false;
        }
        if(request.action()==UiPayloads.Action.MANAGE) {return ManagementService.open(player,settlement.id());}
        if(request.action()==UiPayloads.Action.PREVIEW) {
            if(!session.construction || session.npc==null || session.shortcut || session.marker!=null || session.board!=null) return false;
            var kind=BuildingPreviewTargets.building(request.quest());
            boolean result=kind.isPresent() && dev.livingkingdoms.construction.preview.ConstructionPreviewService.begin(player,settlement.id(),kind.get());
            if(result) dismiss(player);else sendConstruction(player,session,settlement,true);return result;
        }
        if(request.action()==UiPayloads.Action.STOCK) {
            var view=session.stockView();SESSIONS.put(player,view);sendStock(player,view,settlement,null);return true;
        }
        if(request.action()==UiPayloads.Action.STOCK_DEPOSIT || request.action()==UiPayloads.Action.STOCK_WITHDRAW) {
            if(!session.stock) return false;
            var resource=StockUiState.resource(request.quest());int moved=0;
            if(resource.isPresent()) moved=request.action()==UiPayloads.Action.STOCK_DEPOSIT
                    ? dev.livingkingdoms.economy.KingdomEconomy.deposit(player,settlement.id(),resource.get(),16)
                    : dev.livingkingdoms.economy.KingdomEconomy.withdraw(player,settlement.id(),resource.get(),resource.get()==dev.livingkingdoms.economy.StockResource.EQUIPMENT?1:16);
            sendStock(player,session,settlement,moved>0?"ui.livingkingdoms.stock.changed":"ui.livingkingdoms.stock.rejected");return moved>0;
        }
        if(session.stock && request.action()==UiPayloads.Action.REFRESH) {sendStock(player,session,settlement,null);return true;}
        if(session.stock && request.action()!=UiPayloads.Action.CITIZENS && request.action()!=UiPayloads.Action.INFO
                && request.action()!=UiPayloads.Action.CONSTRUCTION && request.action()!=UiPayloads.Action.BOARD && request.action()!=UiPayloads.Action.IMMIGRATION) return false;
        if(request.action()==UiPayloads.Action.TRACK || request.action()==UiPayloads.Action.UNTRACK) {
            if(session.board==null || session.construction || session.citizens || session.immigration || session.stock) return false;
            var quests=QuestSavedData.get(player.server);
            if(quests.quest(player.getUUID(),request.quest()).filter(q -> q.source().settlementId().equals(session.settlement)).isEmpty()) return false;
            boolean result=request.action()==UiPayloads.Action.TRACK?quests.track(player.getUUID(),request.quest()):quests.untrack(player.getUUID(),request.quest());
            sendBoard(player,session,settlement,!result); return result;
        }
        if(request.action()==UiPayloads.Action.CITIZENS) {
            var citizens=session.citizensView(); SESSIONS.put(player,citizens); sendCitizens(player,citizens,settlement,null); return true;
        }
        if(request.action()==UiPayloads.Action.ASSIGN_FARMER || request.action()==UiPayloads.Action.ASSIGN_GUARD
                || request.action()==UiPayloads.Action.ASSIGN_BUILDER || request.action()==UiPayloads.Action.REMOVE_PROFESSION
                || request.action()==UiPayloads.Action.ASSIGN_LUMBERJACK || request.action()==UiPayloads.Action.ASSIGN_MINER || request.action()==UiPayloads.Action.ASSIGN_BLACKSMITH || request.action()==UiPayloads.Action.ASSIGN_MERCHANT) {
            if(!session.citizens) return false;
            var jobs=dev.livingkingdoms.profession.persistence.ProfessionSavedData.get(player.server);
            var expected=CITIZEN_VIEWS.getOrDefault(player,Map.of()).get(request.quest());
            if(expected==null || expected!=jobs.employmentRevision(request.quest())) {
                sendCitizens(player,session,settlement,"ui.livingkingdoms.citizens.rejected"); return false;
            }
            boolean result=request.action()==UiPayloads.Action.ASSIGN_FARMER
                    ? dev.livingkingdoms.profession.ProfessionService.assign(player,settlement.id(),request.quest())
                    : request.action()==UiPayloads.Action.ASSIGN_GUARD
                    ? dev.livingkingdoms.profession.ProfessionService.assignGuard(player,settlement.id(),request.quest())
                    : request.action()==UiPayloads.Action.ASSIGN_BUILDER
                    ? dev.livingkingdoms.profession.ProfessionService.assignBuilder(player,settlement.id(),request.quest())
                    : request.action()==UiPayloads.Action.ASSIGN_MERCHANT
                    ? dev.livingkingdoms.profession.ProfessionService.assignMerchant(player,settlement.id(),request.quest())
                    : request.action()==UiPayloads.Action.ASSIGN_LUMBERJACK || request.action()==UiPayloads.Action.ASSIGN_MINER || request.action()==UiPayloads.Action.ASSIGN_BLACKSMITH
                    ? dev.livingkingdoms.profession.ProfessionService.assignEconomic(player,settlement.id(),request.quest(),switch(request.action()) {
                        case ASSIGN_LUMBERJACK->dev.livingkingdoms.profession.domain.ProfessionType.LUMBERJACK;
                        case ASSIGN_MINER->dev.livingkingdoms.profession.domain.ProfessionType.MINER;
                        default->dev.livingkingdoms.profession.domain.ProfessionType.BLACKSMITH;
                    })
                    : dev.livingkingdoms.profession.ProfessionService.remove(player,settlement.id(),request.quest());
            sendCitizens(player,session,settlement,result?"ui.livingkingdoms.citizens.changed":"ui.livingkingdoms.citizens.rejected"); return result;
        }
        if(session.citizens && request.action()==UiPayloads.Action.REFRESH) { sendCitizens(player,session,settlement,null); return true; }
        if(session.citizens && request.action()!=UiPayloads.Action.INFO && request.action()!=UiPayloads.Action.BOARD
                && request.action()!=UiPayloads.Action.CONSTRUCTION && request.action()!=UiPayloads.Action.IMMIGRATION) return false;
        if (request.action() == UiPayloads.Action.IMMIGRATION) {
            Session immigration=session.immigrationView(); SESSIONS.put(player,immigration);
            sendImmigration(player,immigration,settlement,null);
            return true;
        }
        if (request.action() == UiPayloads.Action.ACCEPT_CITIZEN || request.action() == UiPayloads.Action.DECLINE_CITIZEN) {
            // The session is a capability for this view, not permission to mutate arbitrary candidates.
            if (!session.immigration) return false;
            boolean accepted=request.action()==UiPayloads.Action.ACCEPT_CITIZEN;
            boolean result;
            try {
                result=accepted ? ImmigrationService.accept(player,settlement.id(),request.quest())
                        : ImmigrationService.decline(player,settlement.id(),request.quest());
            } catch (RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("Immigration interaction rejected for {}: {}",settlement.id(),failure.toString());
                result=false;
            }
            sendImmigration(player,session,SettlementSavedData.get(player.server).get(settlement.id()).orElse(settlement),result ? accepted ? "ui.livingkingdoms.immigration.accepted"
                    : "ui.livingkingdoms.immigration.declined" : "ui.livingkingdoms.immigration.rejected");
            return result;
        }
        if (session.immigration && request.action()==UiPayloads.Action.REFRESH) {
            sendImmigration(player,session,settlement,null); return true;
        }
        if (session.immigration && request.action()!=UiPayloads.Action.INFO
                && request.action()!=UiPayloads.Action.BOARD && request.action()!=UiPayloads.Action.CONSTRUCTION) return false;
        if (request.action()==UiPayloads.Action.INFO || request.action()==UiPayloads.Action.TALK || request.action()==UiPayloads.Action.BOARD) {
            // Navigation clears the mode while retaining the physical anchor and original nonce.
            SESSIONS.put(player,session.originalView());
        }
        if (request.action() == UiPayloads.Action.CONSTRUCTION || session.construction
                && request.action()!=UiPayloads.Action.INFO && request.action()!=UiPayloads.Action.TALK && request.action()!=UiPayloads.Action.BOARD) {
            Session construction=session.constructionView(); SESSIONS.put(player,construction);
            boolean result;
            try { result=switch(request.action()) {
                case CONSTRUCTION, REFRESH -> true;
                case DEPOSIT -> dev.livingkingdoms.construction.ConstructionService.deposit(player,settlement.id(),request.quest());
                case DEPOSIT_STOCK -> dev.livingkingdoms.construction.ConstructionService.depositStock(player,settlement.id(),request.quest());
                case RETRY -> dev.livingkingdoms.construction.ConstructionService.retry(player,settlement.id(),request.quest());
                case PLAN -> settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.FOUNDING
                        ? dev.livingkingdoms.construction.ConstructionService.ensureNext(player.serverLevel(),settlement.id(),player)
                        : dev.livingkingdoms.construction.ConstructionService.planHouse(player.serverLevel(),settlement.id(),player);
                case PLAN_BARRACKS -> dev.livingkingdoms.construction.ConstructionService.planBarracks(player.serverLevel(),settlement.id(),player);
                case PLAN_WAREHOUSE,PLAN_LUMBERJACK_HUT,PLAN_MINE,PLAN_BLACKSMITH -> dev.livingkingdoms.construction.ConstructionService.planEconomicBuilding(player.serverLevel(),settlement.id(),player,switch(request.action()) {
                    case PLAN_WAREHOUSE->dev.livingkingdoms.structure.BuildingKind.WAREHOUSE;
                    case PLAN_LUMBERJACK_HUT->dev.livingkingdoms.structure.BuildingKind.LUMBERJACK_HUT;
                    case PLAN_MINE->dev.livingkingdoms.structure.BuildingKind.MINE;
                    default->dev.livingkingdoms.structure.BuildingKind.BLACKSMITH;
                });
                case PLAN_WATCHTOWER -> dev.livingkingdoms.construction.ConstructionService.planWatchtower(player.serverLevel(),settlement.id(),player);
                case PLAN_FARM -> dev.livingkingdoms.construction.ConstructionService.planFarm(player.serverLevel(),settlement.id(),player);
                default -> false;
            }; } catch (RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("Construction interaction rejected for {}: {}",settlement.id(),failure.toString());
                result=false;
            }
            sendConstruction(player,construction,SettlementSavedData.get(player.server).get(settlement.id()).orElseThrow(),!result);
            return result;
        }
        if (session.marker != null) return false;
        if (session.board != null) {
            if (ExpandedQuestService.boardSettlement(player, session.board).filter(s -> s.id().equals(session.settlement)).isEmpty()) return invalidate(player);
            boolean result = switch (request.action()) {
                case ACCEPT, CLAIM -> ExpandedQuestService.act(player, session.board, request.quest(), request.action() == UiPayloads.Action.ACCEPT ? "accept" : "claim", false);
                case REFRESH, BOARD -> true;
                default -> false;
            };
            sendBoard(player, session, settlement, !result);
            return result;
        }
        if(session.shortcut) {
            if(request.action()==UiPayloads.Action.BOARD) return QuestJournalService.open(player);
            if(request.action()==UiPayloads.Action.INFO || request.action()==UiPayloads.Action.TALK || request.action()==UiPayloads.Action.REFRESH) {
                sendDialogue(player,session,settlement,null,true); return true;
            }
            return false;
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
        Session session = SESSIONS.remove(player);
        CompoundTag closed = new CompoundTag(); closed.putString("screen", "closed");
        if (session != null) closed.putUUID("session", session.token);
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
        dev.livingkingdoms.defense.DefenseService.awardPending(player);
        dev.livingkingdoms.profession.ProfessionService.ensure(player.serverLevel(),settlement);
        var candidates=ImmigrationService.candidates(player.serverLevel(),settlement);
        var citizens=CitizenSavedData.get(player.server);
        var housing=citizens.summary(settlement.id());
        CompoundTag tag = new CompoundTag();
        tag.putUUID("session", session.token); tag.putString("screen", screen);
        tag.putString("settlement", VillageNames.display(settlement));
        tag.putInt("reputation", QuestSavedData.get(player.server).reputation(player.getUUID(), settlement.id()));
        tag.putString("lifecycle",settlement.lifecycle().name());
        tag.putBoolean("construction",true);
        tag.putBoolean("can_help", KingdomOnboardingService.canHelp(player, settlement));
        tag.putInt("population",CitizenService.population(player.server,settlement));
        tag.putInt("housing_total",housing.total()); tag.putInt("housing_occupied",housing.occupied()); tag.putInt("housing_free",housing.free());
        tag.putInt("immigration_pending",candidates.size());
        tag.putInt("security",dev.livingkingdoms.profession.SecurityService.score(player.server,settlement.id()));
        tag.putString("safety",dev.livingkingdoms.defense.DefenseService.status(player.server,settlement.id()).name().toLowerCase(java.util.Locale.ROOT));
        tag.putBoolean("barracks_plan",!dev.livingkingdoms.construction.ConstructionService.planned(player.server,settlement.id(),dev.livingkingdoms.structure.BuildingKind.BARRACKS)
                && dev.livingkingdoms.profession.persistence.ProfessionSavedData.get(player.server).buildings(settlement.id()).stream().noneMatch(b -> b.kind()==dev.livingkingdoms.structure.BuildingKind.BARRACKS));
        tag.putBoolean("watchtower_plan",!dev.livingkingdoms.construction.ConstructionService.planned(player.server,settlement.id(),dev.livingkingdoms.structure.BuildingKind.WATCHTOWER)
                && dev.livingkingdoms.profession.persistence.ProfessionSavedData.get(player.server).buildings(settlement.id()).stream().noneMatch(b -> b.kind()==dev.livingkingdoms.structure.BuildingKind.WATCHTOWER));
        dev.livingkingdoms.defense.DefenseService.current(player.server,settlement.id())
                .filter(event -> event.state()!=dev.livingkingdoms.defense.domain.ThreatState.APPROACHING).ifPresent(event -> DefenseUiState.write(tag,event,player.getUUID()));
        var food=dev.livingkingdoms.profession.ProfessionService.food(player.server,settlement.id());
        tag.putInt("food_stock",food.stock()); tag.putInt("food_capacity",food.capacity()); tag.putLong("food_produced",food.produced());
        tag.putInt("food_reserved",food.reserve(dev.livingkingdoms.config.ProfessionConfig.HEALTHY_FOOD.get()));
        return tag;
    }

    private static void sendDialogue(ServerPlayer player, Session session, Settlement settlement, Villager npc, boolean info) {
        sendDialogue(player, session, settlement, npc, info, false);
    }

    private static void sendDialogue(ServerPlayer player, Session session, Settlement settlement, Villager npc, boolean info, boolean rejected) {
        CompoundTag tag = base(player, session, settlement, "dialogue");
        tag.putInt("entity", npc==null?-1:npc.getId());
        tag.putString("name", npc==null?VillageNames.display(settlement):MayorPresentation.name(npc));
        tag.putBoolean("shortcut",session.shortcut);
        tag.putBoolean("readonly",!inside(player,settlement) || !tag.getBoolean("can_help"));
        tag.putString("role", "mayor");
        tag.putBoolean("info",info);
        tag.putString("dialogue", info ? "ui.livingkingdoms.dialogue.info"
                : settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.FOUNDING ? "ui.livingkingdoms.dialogue.founding"
                : QuestSavedData.get(player.server).progress(player.getUUID(), settlement.id()).state() == QuestState.COMPLETED
                ? "npc.livingkingdoms.mayor.after" : "npc.livingkingdoms.mayor.before");
        if(!info) dev.livingkingdoms.defense.DefenseService.current(player.server,settlement.id())
                .or(() -> dev.livingkingdoms.defense.DefenseService.latest(player.server,settlement.id()))
                .filter(e -> e.state()!=dev.livingkingdoms.defense.domain.ThreatState.APPROACHING)
                .ifPresent(event -> tag.putString("dialogue",DefenseUiState.dialogue(event,player.getUUID())));
        tag.putInt("level", settlement.level());
        if (npc != null) KingdomOnboardingService.writeView(player, settlement, tag);
        if (rejected) tag.putString("notice", "onboarding.livingkingdoms.rejected");
        PacketDistributor.sendToPlayer(player, new UiPayloads.Snapshot(tag));
    }

    private static void sendConstruction(ServerPlayer player,Session session,Settlement settlement,boolean rejected) {
        CompoundTag tag=base(player,session,settlement,"construction");
        tag.putBoolean("preview_allowed",session.npc!=null && !session.shortcut && session.board==null && session.marker==null);
        if(rejected) tag.putString("notice","construction.livingkingdoms.rejected");
        ListTag projects=new ListTag();
        var entries=dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).projects(settlement.id()).stream()
                .sorted(java.util.Comparator.comparingInt((dev.livingkingdoms.construction.persistence.ConstructionSavedData.Entry e) ->
                        e.project().state()==dev.livingkingdoms.construction.domain.ConstructionState.BUILDING ? 0
                        : e.project().state()==dev.livingkingdoms.construction.domain.ConstructionState.COMPLETED ? 2 : 1)).toList();
        var people=CitizenSavedData.get(player.server);
        var jobs=dev.livingkingdoms.profession.persistence.ProfessionSavedData.get(player.server);
        for(var entry:entries) {
            var p=entry.project(); CompoundTag view=new CompoundTag(); view.putUUID("id",p.id());
            view.putString("building",p.building().name().toLowerCase(java.util.Locale.ROOT)); view.putString("state",p.state().name());
            view.putInt("x",p.plot().x()); view.putInt("y",p.plot().y()); view.putInt("z",p.plot().z());
            view.putDouble("progress",p.progress(now(player))); view.putBoolean("awaiting_chunks",p.awaitingChunks());
            view.putBoolean("builder_required",p.builderRequired()); view.putInt("stage",p.visualStage());
            view.putString("queue",p.state()==dev.livingkingdoms.construction.domain.ConstructionState.COMPLETED ? ""
                    : p.state()==dev.livingkingdoms.construction.domain.ConstructionState.BUILDING ? "active" : "next");
            double speed=1;
            if(p.builderId()!=null) {
                people.citizen(p.builderId()).ifPresent(c -> view.putString("builder",c.name()));
                var job=jobs.profession(p.builderId()).orElse(null);
                if(job!=null) {
                    view.putString("builder_status",job.workState().name().toLowerCase(java.util.Locale.ROOT));
                    speed=dev.livingkingdoms.profession.domain.BuilderPolicy.speedMultiplier(job.level().value(),
                            dev.livingkingdoms.config.BuilderConfig.LEVEL_CAP.get(),dev.livingkingdoms.config.BuilderConfig.MAX_SPEED_BONUS.get());
                }
            }
            view.putLong("duration",p.durationTicks());
            view.putBoolean("waiting_site",dev.livingkingdoms.construction.persistence.ConstructionSavedData.get(player.server).waitingForClearSite(p.id()));
            view.putLong("remaining",p.builderRequired() ? (long)Math.ceil(p.remainingWork()/(20*speed))
                    : p.startedAt()<0 ? p.durationTicks()/20 : Math.max(0,(p.deadline()-now(player)+19)/20));
            ListTag costs=new ListTag();boolean storedAvailable=false,carriedAvailable=false;
            for(var kind:ResourceKind.values()) if(p.required().containsKey(kind)) {
                CompoundTag cost=new CompoundTag(); cost.putString("item",kind.id()); cost.putInt("required",p.required().get(kind));
                cost.putInt("count",p.supplied().getOrDefault(kind,0)); cost.putInt("carried",DeliveryInventory.count(player.getInventory(),kind));
                int stored=dev.livingkingdoms.economy.KingdomEconomy.itemCount(player.server,settlement.id(),dev.livingkingdoms.economy.StockResource.of(kind));cost.putInt("stored",stored);costs.add(cost);
                if(p.missing(kind)>0) {storedAvailable|=stored>0;carriedAvailable|=cost.getInt("carried")>0;}
            }
            view.put("requirements",costs);view.putBoolean("stock_available",storedAvailable);view.putBoolean("carried_available",carriedAvailable);projects.add(view);
        }
        tag.put("projects",projects); tag.putBoolean("plan",settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED
                ? dev.livingkingdoms.construction.ConstructionService.canPlan(player.server,settlement.id())
                : settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.FOUNDING
                    && entries.stream().noneMatch(e -> e.project().state()!=dev.livingkingdoms.construction.domain.ConstructionState.COMPLETED));
        PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(tag));
    }

    private static void sendImmigration(ServerPlayer player,Session session,Settlement settlement,String notice) {
        CompoundTag tag=base(player,session,settlement,"immigration");
        if(notice!=null) tag.putString("notice",notice);
        tag.putString("return_action",session.marker!=null ? "CONSTRUCTION" : session.board!=null ? "BOARD" : "INFO");
        boolean established=settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED;
        tag.putBoolean("immigration_enabled",CitizenConfig.IMMIGRATION_ENABLED.get());
        tag.putInt("immigration_required_housing",CitizenConfig.MIN_FREE_HOUSING.get());
        tag.putBoolean("immigration_accept",established && CitizenConfig.IMMIGRATION_ENABLED.get()
                && tag.getInt("housing_free")>=CitizenConfig.MIN_FREE_HOUSING.get());
        ListTag candidates=new ListTag();
        for(var candidate:ImmigrationService.candidates(player.serverLevel(),settlement)) {
            CompoundTag view=new CompoundTag(); view.putUUID("id",candidate.id());
            view.putString("name",candidate.name()); view.putInt("level",candidate.level().value());
            view.putString("role",candidate.preferredRole().name().toLowerCase(java.util.Locale.ROOT));
            view.putLong("expires",Math.max(0,(candidate.expiresAt()-now(player)+19)/20));
            candidates.add(view);
        }
        tag.put("candidates",candidates);
        PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(tag));
    }

    private static void sendStock(ServerPlayer player,Session session,Settlement settlement,String notice) {
        var tag=base(player,session,settlement,"stock");if(notice!=null) tag.putString("notice",notice);
        StockUiState.write(player,settlement.id(),tag);PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(tag));
    }

    private static void sendCitizens(ServerPlayer player,Session session,Settlement settlement,String notice) {
        CompoundTag tag=base(player,session,settlement,"citizens"); if(notice!=null) tag.putString("notice",notice);
        tag.putString("return_action",session.marker!=null?"CONSTRUCTION":session.board!=null?"BOARD":"INFO");
        var people=CitizenSavedData.get(player.server); var jobs=dev.livingkingdoms.profession.persistence.ProfessionSavedData.get(player.server);
        var buildings=jobs.buildings(settlement.id()); var list=new ListTag();
        var stock=new CompoundTag();
        for(var resource:dev.livingkingdoms.economy.StockResource.values()) stock.putInt(resource.name(),dev.livingkingdoms.economy.KingdomEconomy.count(player.server,settlement.id(),resource));
        tag.put("stock",stock);
        var revisions=new java.util.HashMap<UUID,Long>(); CITIZEN_VIEWS.put(player,revisions);
        var roster=people.citizens(settlement.id()).stream().sorted(java.util.Comparator.comparing((dev.livingkingdoms.citizen.domain.Citizen c) -> c.state()!=dev.livingkingdoms.citizen.domain.CitizenState.ACTIVE)
                .thenComparing(dev.livingkingdoms.citizen.domain.Citizen::name).thenComparing(dev.livingkingdoms.citizen.domain.Citizen::id)).toList();
        tag.putInt("roster_total",roster.size());
        for(var citizen:roster.stream().limit(256).toList()) {
            revisions.put(citizen.id(),jobs.employmentRevision(citizen.id()));
            var p=jobs.profession(citizen.id()).orElseThrow(); var v=new CompoundTag(); v.putUUID("id",citizen.id()); v.putString("name",citizen.name()); v.putInt("level",citizen.level().value());
            v.putString("profession",p.type().name().toLowerCase(java.util.Locale.ROOT)); v.putInt("profession_level",p.level().value()); v.putLong("xp",p.experience());
            v.putString("status",citizen.state().name().toLowerCase(java.util.Locale.ROOT)); v.putString("work_state",p.workState().name().toLowerCase(java.util.Locale.ROOT)); v.putBoolean("active",p.active());
            if(citizen.homeId()!=null) people.housing(citizen.homeId()).ifPresent(h -> {v.putBoolean("home",true);v.putInt("home_x",h.position().getX());v.putInt("home_z",h.position().getZ());});
            if(p.workplaceId()!=null) jobs.building(p.workplaceId()).ifPresent(b -> {v.putBoolean("workplace",true);v.putString("workplace_kind",b.kind().name().toLowerCase(java.util.Locale.ROOT));v.putInt("work_x",b.origin().getX());v.putInt("work_z",b.origin().getZ());v.putInt("workers",jobs.workers(b.id()));v.putInt("slots",b.workplaceSlots());});
            v.putBoolean("assign",settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED
                    && dev.livingkingdoms.profession.ProfessionService.canAssignOperational(player.serverLevel(),citizen,p,buildings,jobs,dev.livingkingdoms.profession.domain.ProfessionType.FARMER));
            v.putBoolean("remove",citizen.state()==dev.livingkingdoms.citizen.domain.CitizenState.ACTIVE && p.type().operational());
            v.putBoolean("assign_guard",settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED
                    && dev.livingkingdoms.profession.ProfessionService.canAssignOperational(player.serverLevel(),citizen,p,buildings,jobs,dev.livingkingdoms.profession.domain.ProfessionType.GUARD));
            v.putBoolean("assign_builder",settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED
                    && dev.livingkingdoms.profession.ProfessionService.canAssignBuilder(player.serverLevel(),citizen,p,buildings,jobs));
            for(var type:java.util.List.of(dev.livingkingdoms.profession.domain.ProfessionType.LUMBERJACK,dev.livingkingdoms.profession.domain.ProfessionType.MINER,dev.livingkingdoms.profession.domain.ProfessionType.BLACKSMITH,dev.livingkingdoms.profession.domain.ProfessionType.MERCHANT))
                v.putBoolean("assign_"+type.name().toLowerCase(java.util.Locale.ROOT),settlement.lifecycle()==dev.livingkingdoms.settlement.domain.SettlementLifecycle.ESTABLISHED
                        && dev.livingkingdoms.profession.ProfessionService.canAssignOperational(player.serverLevel(),citizen,p,buildings,jobs,type));
            if(p.type()==dev.livingkingdoms.profession.domain.ProfessionType.BUILDER)
                dev.livingkingdoms.construction.ConstructionService.projectForBuilder(player.server,citizen.id())
                        .ifPresent(e -> v.putString("project_building",e.project().building().name().toLowerCase(java.util.Locale.ROOT)));
            list.add(v);
        }
        tag.put("citizens",list); PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(tag));
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
        if (data.mainSettlement(player.getUUID()).isEmpty() || data.mainSettlement(player.getUUID()).filter(settlement.id()::equals).isPresent()) {
            var completed = data.completedMain(player.getUUID(), settlement.id());
            var offered = data.quests(player.getUUID(), settlement.id()).stream().map(QuestInstance::template).collect(java.util.stream.Collectors.toSet());
            for (var template : QuestCatalog.mainChain()) {
                if (completed.contains(template) || offered.contains(template)) continue;
                CompoundTag locked = new CompoundTag();
                locked.putUUID("id", UUID.nameUUIDFromBytes((settlement.id() + ":locked:" + template.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                locked.putString("template", template.id()); locked.putString("category", "MAIN"); locked.putString("state", "LOCKED");
                locked.putInt("stage", template.stage()); locked.putInt("level", Math.max(1, template.order()));
                locked.putString("difficulty", QuestDifficulty.VERY_EASY.titleKey()); locked.putLong("expires", -1);
                locked.putString("objective", "quest.livingkingdoms." + template.id() + ".objective");
                locked.putString("locked_reason", !tag.getBoolean("can_help") ? "onboarding.livingkingdoms.required"
                        : QuestCatalog.prerequisitesMet(template, completed) ? "quest.livingkingdoms.waiting_context" : "quest.livingkingdoms.previous_step");
                entries.add(locked);
            }
        }
        tag.put("quests", entries);
        var anchor = data.mainSettlement(player.getUUID());
        tag.putString("hint", anchor.filter(settlement.id()::equals).isPresent() ? "quest.livingkingdoms.follow_chapter" : "quest.livingkingdoms.meet_mayor");
        if (anchor.isPresent() && !anchor.get().equals(settlement.id())) {
            tag.putString("hint", "ui.livingkingdoms.main_elsewhere");
        }
        PacketDistributor.sendToPlayer(player, new UiPayloads.Snapshot(tag));
    }

    public static CompoundTag questView(ServerPlayer player, QuestInstance quest) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", quest.id()); tag.putString("template", quest.template().id());
        tag.putString("settlement", SettlementSavedData.get(player.server).get(quest.source().settlementId()).map(VillageNames::display).orElse(""));
        tag.putInt("stage", quest.template().stage());
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
                    tag.putInt("party_remaining",target.remainingMembers().size());
                    tag.putInt("party_total",target.memberIds().size());
                });
                if(quest.template()==QuestTemplate.LOCAL_DEFENSE) {
                    tag.putString("settlement",SettlementSavedData.get(player.server).get(quest.source().settlementId())
                            .map(VillageNames::display).orElse(""));
                    var event=dev.livingkingdoms.defense.DefenseService.eventForParty(player.server,party.partyId());
                    event.ifPresent(value -> DefenseUiState.write(tag,value,player.getUUID()));
                    if(event.isEmpty()) tag.putString("defense_note","ui.livingkingdoms.defense.missing");
                    tag.putString("objective","quest.livingkingdoms.local_defense.objective");
                }
            }
            case QuestObjective.Meet ignored -> tag.putString("objective", quest.template() == QuestTemplate.MAIN_RESIDENTS
                    ? "quest.livingkingdoms.main_residents.objective" : "quest.livingkingdoms.meet_mayor");
            case QuestObjective.Return ignored -> tag.putString("objective", quest.template() == QuestTemplate.MAIN_DEVELOPMENT
                    || quest.template() == QuestTemplate.MAIN_WATCHTOWER ? "quest.livingkingdoms." + quest.template().id() + ".objective"
                    : "quest.livingkingdoms.return_objective");
        }
        tag.put("requirements", requirements); tag.putBoolean("ready", ready);
        boolean tracked=QuestSavedData.get(player.server).tracked(player.getUUID()).map(q -> q.id().equals(quest.id())).orElse(false);
        tag.putBoolean("tracked",tracked); tag.putBoolean("track",quest.state()==QuestState.ACTIVE); tag.putBoolean("untrack",tracked);
        tag.putBoolean("accept", ExpandedQuestService.canAccept(player, quest));
        tag.putBoolean("claim", quest.state() == QuestState.ACTIVE && ready);
        return tag;
    }
    private static boolean inside(ServerPlayer player, Settlement settlement) {
        var pos = player.blockPosition();
        return settlement.territory().contains(player.level().dimension().location().toString(), pos.getX(), pos.getZ());
    }

    private static long now(ServerPlayer player) { return player.server.overworld().getGameTime(); }
}

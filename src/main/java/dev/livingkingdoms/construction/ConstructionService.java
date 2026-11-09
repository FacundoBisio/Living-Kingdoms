package dev.livingkingdoms.construction;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.ConstructionConfig;
import dev.livingkingdoms.config.BuilderConfig;
import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.*;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.construction.domain.*;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.quest.DeliveryInventory;
import dev.livingkingdoms.quest.expansion.domain.*;
import dev.livingkingdoms.settlement.*;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.entity.npc.Villager;
import java.util.*;

/** All mutations are synchronous on the server thread. Clients never supply counts, costs, time or plots. */
public final class ConstructionService {
    private static final Map<UUID,Long> SITE_RETRY=new LinkedHashMap<>();
    private static final Map<UUID,LoadedPlan> CHUNKS=new LinkedHashMap<>();
    private static final Map<UUID,WorkPoint> WORK_POINTS=new LinkedHashMap<>();
    /** Transient, bounded diagnostics: foreign blocks to clear and the last visible blocking reason per project. */
    private static final Map<UUID,List<BlockPos>> OBSTACLES=new LinkedHashMap<>();
    private static final Map<UUID,Reason> REASONS=new LinkedHashMap<>();
    /** Helper Builders per project: citizen -> last game time they helped. */
    private static final Map<UUID,Map<UUID,Long>> ASSISTANTS=new LinkedHashMap<>();
    public static final int MAX_ASSISTANTS=2;
    public record Reason(String key,List<String> args) {}
    private record LoadedPlan(SettlementLayout plan,Set<Long> chunks) {}
    private record WorkPoint(SettlementLayout plan,BlockPos feet) {}
    private ConstructionService() {}
    public static Optional<ConstructionSavedData.Entry> current(MinecraftServer server,UUID settlement) {
        var projects=ConstructionSavedData.get(server).projects(settlement);
        return projects.stream().filter(e -> e.project().state()==ConstructionState.BUILDING).findFirst()
                .or(() -> projects.stream().filter(e -> e.project().state()!=ConstructionState.COMPLETED).findFirst());
    }
    public static boolean canPlan(MinecraftServer server,UUID settlement) {
        return ConstructionSavedData.get(server).projects(settlement).stream().filter(e -> e.project().state()!=ConstructionState.COMPLETED).count()<ConstructionConfig.QUEUE_LIMIT.get();
    }
    public static boolean planned(MinecraftServer server,UUID settlement,BuildingKind kind) {
        return ConstructionSavedData.get(server).projects(settlement).stream().anyMatch(e -> e.project().building()==kind && e.project().state()!=ConstructionState.COMPLETED);
    }
    public static boolean ensureNext(ServerLevel level,UUID settlementId,ServerPlayer actor) {
        var data=SettlementSavedData.get(level.getServer()); var settlement=data.get(settlementId).orElseThrow();
        if(settlement.lifecycle()!=SettlementLifecycle.FOUNDING || current(level.getServer(),settlementId).isPresent()) return true;
        var layout=data.layout(settlementId).orElseThrow();
        boolean hall=layout.buildings().stream().anyMatch(b -> b.kind()==BuildingKind.TOWN_HALL);
        boolean house=layout.buildings().stream().anyMatch(b -> b.kind()==BuildingKind.HOUSE || b.kind()==BuildingKind.HOUSE_VARIANT || b.kind()==BuildingKind.HOUSE_THIRD);
        if(hall && house) { data.replace(settlement,settlement.withLifecycle(SettlementLifecycle.ESTABLISHED)); return true; }
        var kind=foundingNext(hall,house,ConstructionConfig.HOUSE_FIRST.get());
        var plan=new SettlementGenerator().planBuildingAddition(level,settlementId,kind,new GenerationDiagnostics());
        if(plan.isEmpty()) return false;
        reserve(level,settlementId,plan.orElseThrow(),actor); return true;
    }
    /** Founding order. House-first gives the founders a home before the larger civic project. */
    public static BuildingKind foundingNext(boolean hall,boolean house,boolean houseFirst) {
        if(houseFirst) return house ? BuildingKind.TOWN_HALL : BuildingKind.HOUSE;
        return hall ? BuildingKind.HOUSE : BuildingKind.TOWN_HALL;
    }
    /** Small survival expansion using the existing materials, timer and protected-placement system. */
    public static boolean planHouse(ServerLevel level,UUID settlementId,ServerPlayer actor) {
        return planFunctional(level,settlementId,actor,BuildingKind.HOUSE);
    }
    public static boolean planFarm(ServerLevel level,UUID settlementId,ServerPlayer actor) {
        return planFunctional(level,settlementId,actor,BuildingKind.FARM);
    }
    public static boolean planBarracks(ServerLevel level,UUID settlementId,ServerPlayer actor) {
        return planFunctional(level,settlementId,actor,BuildingKind.BARRACKS);
    }
    public static boolean planWatchtower(ServerLevel level,UUID settlementId,ServerPlayer actor) {
        return planFunctional(level,settlementId,actor,BuildingKind.WATCHTOWER);
    }
    private static boolean planFunctional(ServerLevel level,UUID settlementId,ServerPlayer actor,BuildingKind kind) {
        var data=SettlementSavedData.get(level.getServer()); var settlement=data.get(settlementId).orElse(null);
        if(actor==null || !canContribute(actor,settlement) || settlement.lifecycle()!=SettlementLifecycle.ESTABLISHED
                || !canPlan(level.getServer(),settlementId)) return false;
        if((kind==BuildingKind.BARRACKS || kind==BuildingKind.WATCHTOWER)
                && (planned(level.getServer(),settlementId,kind) || data.layout(settlementId).stream().flatMap(l -> l.buildings().stream()).anyMatch(b -> b.kind()==kind))) return false;
        if(data.layout(settlementId).isEmpty()) {
            if(settlement.provenance().origin()!=SettlementOrigin.CONVERTED) return false;
            var marker=new BlockPos(settlement.territory().x(),settlement.territory().y(),settlement.territory().z());
            // Only infrastructure-adjacent loaded ground; vanilla buildings/beds are never invented as LK metadata.
            var ports=java.util.stream.Stream.of(marker.east().below(),marker.west().below(),marker.north().below(),marker.south().below())
                    .filter(pos -> level.hasChunkAt(pos) && level.getBlockState(pos).isSolid()
                            && level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.above(2)).isAir()).toList();
            if(ports.isEmpty()) return false;
            data.updateLayout(settlementId,new SettlementLayoutMetadata(ArchitectureStyle.at(level,marker),List.of(),ports,List.of()));
        }
        var plan=new SettlementGenerator().planBuildingAddition(level,settlementId,kind,new GenerationDiagnostics());
        if(plan.isEmpty()) return false;
        reserve(level,settlementId,plan.orElseThrow(),actor); return true;
    }
    /** Explicit survival plans use the same terrain/protection/reservation pipeline. */
    public static boolean planEconomicBuilding(ServerLevel level,UUID settlementId,ServerPlayer actor,BuildingKind kind) {
        return switch(kind) {
            case WAREHOUSE,LUMBERJACK_HUT,MINE,BLACKSMITH,MARKET -> planFunctional(level,settlementId,actor,kind);
            default -> false;
        };
    }
    public static boolean depositStock(ServerPlayer player,UUID settlementId,UUID projectId) {
        if(!dev.livingkingdoms.economy.KingdomEconomy.authorized(player,settlementId)) return false;
        return supplyFromStock(player.server,settlementId,projectId,0);
    }
    /**
     * Supplies a WAITING_FOR_RESOURCES project of an allied settlement from its shared stock, leaving
     * {@code keepBack} items of each required material in storage. Partial supply is allowed. The economy
     * transfer and project receipt use the same compare-and-set order as the manual deposit: validate,
     * replace the project, then commit the stock. Returns true only when materials were moved.
     * Callers own authorization; this method performs no player checks.
     */
    public static boolean supplyFromStock(MinecraftServer server,UUID settlementId,UUID projectId,int keepBack) {
        if(!server.isSameThread()) throw new IllegalStateException("Construction funding requires the server thread");
        if(keepBack<0) throw new IllegalArgumentException("Negative keep-back");
        if(SettlementSavedData.get(server).get(settlementId).filter(s -> s.faction().isAllied()).isEmpty()) return false;
        var storage=ConstructionSavedData.get(server);var entry=storage.get(projectId).orElse(null);
        if(entry==null || !entry.project().settlementId().equals(settlementId) || entry.project().state()!=ConstructionState.WAITING_FOR_RESOURCES) return false;
        var p=entry.project();var delivery=new EnumMap<ResourceKind,Integer>(ResourceKind.class);
        var costs=new EnumMap<dev.livingkingdoms.economy.StockResource,Integer>(dev.livingkingdoms.economy.StockResource.class);
        for(var kind:p.required().keySet()) {
            var resource=dev.livingkingdoms.economy.StockResource.of(kind);
            int amount=supplyAmount(p.missing(kind),dev.livingkingdoms.economy.KingdomEconomy.itemCount(server,settlementId,resource),keepBack);
            if(amount>0) {delivery.put(kind,amount);costs.put(resource,resource.unitsForItems(amount,dev.livingkingdoms.config.ProfessionConfig.WHEAT_FOOD.get()));}
        }
        if(delivery.isEmpty()) return false;
        var transfer=dev.livingkingdoms.economy.KingdomEconomy.plan(server,settlementId,costs,Map.of(),false).orElse(null);
        if(transfer==null) return false;
        var next=p.supply(delivery);if(next.state()==ConstructionState.READY && !next.builderRequired()) next=next.start(now(server));
        // Both stores are preflighted and updated synchronously on the server thread, without hooks/yields.
        if(!dev.livingkingdoms.economy.KingdomEconomy.valid(server,transfer) || !storage.replace(p,next)) return false;
        if(!dev.livingkingdoms.economy.KingdomEconomy.commit(server,transfer)) throw new IllegalStateException("Construction funding changed during commit");
        assignReady(server,settlementId);return true;
    }
    /** Items to move for one material: never more than missing, never dipping stock below the keep-back. */
    public static int supplyAmount(int missing,int stored,int keepBack) {
        return Math.max(0,Math.min(missing,stored-keepBack));
    }
    /** Also reusable by later expansion policies; the initial chain is the only current caller. */
    public static ConstructionSavedData.Entry reserve(ServerLevel level,UUID settlementId,SettlementLayout plan,ServerPlayer actor) {
        var kind=plan.buildings().getFirst().module().kind();
        return reserve(level,settlementId,plan,ConstructionConfig.cost(kind),ConstructionConfig.duration(kind),actor);
    }
    public static ConstructionSavedData.Entry reserve(ServerLevel level,UUID settlementId,SettlementLayout plan,
            Map<ResourceKind,Integer> requirements,long duration,ServerPlayer actor) {
        return reserveWithMetadata(level,settlementId,plan,requirements,duration,actor,
                SettlementSavedData.get(level.getServer()).layout(settlementId).orElseThrow());
    }
    /** Converted villages may survey their native plaza without persisting metadata until confirmation succeeds. */
    public static ConstructionSavedData.Entry reserveWithMetadata(ServerLevel level,UUID settlementId,SettlementLayout plan,
            Map<ResourceKind,Integer> requirements,long duration,ServerPlayer actor,SettlementLayoutMetadata surveyedMetadata) {
        var settlements=SettlementSavedData.get(level.getServer()); var settlement=settlements.get(settlementId).orElseThrow();
        var metadata=settlements.layout(settlementId).orElse(surveyedMetadata);
        if(settlements.layout(settlementId).isEmpty() && settlement.provenance().origin()!=SettlementOrigin.CONVERTED)
            throw new IllegalArgumentException("Only converted villages may initialize a surveyed native plaza");
        if(!settlement.faction().isAllied() || !plan.territory().equals(settlement.territory()) || plan.buildings().size()!=1)
            throw new IllegalArgumentException("Invalid construction context");
        metadata.append(plan).validate(settlement.territory(),settlement.provenance().origin()==SettlementOrigin.CONVERTED);
        if(actor!=null && plan.before().keySet().stream().anyMatch(pos -> !level.mayInteract(actor,pos))) throw new EstablishmentPlacementEvents.Rejected();
        var b=plan.buildings().getFirst(); long now=now(level.getServer());
        if(!canPlan(level.getServer(),settlementId)) throw new IllegalStateException("Construction queue is full");
        var p=ConstructionProject.planned(settlementId,b.module().kind(),new ConstructionProject.Plot(b.origin().getX(),b.origin().getY(),b.origin().getZ(),
                ConstructionProject.Orientation.valueOf(b.rotation().name())),requirements,duration,now);
        // The very first Hall/House keeps founding's legacy clock to avoid a housing/worker deadlock.
        if(settlement.lifecycle()!=SettlementLifecycle.FOUNDING) p=p.requiringBuilder();
        p=p.waitForResources();
        var entry=new ConstructionSavedData.Entry(p,plan); var storage=ConstructionSavedData.get(level.getServer());
        var original=plan.before().get(entry.marker());
        if(original==null || !level.hasChunkAt(entry.marker()) || !level.getBlockState(entry.marker()).equals(original) || !original.isAir())
            throw new IllegalStateException("Construction marker site changed");
        // Preflight reservations before mutating the marker, then roll both back on protection rejection.
        var snapshots=EstablishmentPlacementEvents.capture(level,List.of(entry.marker()));
        storage.reserve(p,plan);
        try {
            if(!level.setBlock(entry.marker(),KingdomBlocks.CONSTRUCTION_MARKER.get().defaultBlockState(),18)) throw new IllegalStateException("Could not place construction marker");
            EstablishmentPlacementEvents.validate(actor,snapshots);
        } catch(RuntimeException failure) {
            level.setBlock(entry.marker(),original,18); storage.removeUnstarted(p); throw failure;
        }
        if(settlements.layout(settlementId).isEmpty()) settlements.updateLayout(settlementId,metadata);
        return entry;
    }
    public static boolean deposit(ServerPlayer player,UUID settlementId,UUID projectId) {
        var storage=ConstructionSavedData.get(player.server); var entry=storage.get(projectId).orElse(null);
        if(entry==null || !entry.project().settlementId().equals(settlementId) || entry.project().state()!=ConstructionState.WAITING_FOR_RESOURCES) return false;
        var settlement=SettlementSavedData.get(player.server).get(settlementId).orElse(null);
        if(!canContribute(player,settlement)) return false;
        var p=entry.project(); Map<ResourceKind,Integer> delivery=new EnumMap<>(ResourceKind.class);
        p.required().keySet().forEach(kind -> { int n=Math.min(p.missing(kind),DeliveryInventory.count(player.getInventory(),kind)); if(n>0) delivery.put(kind,n); });
        if(delivery.isEmpty()) return false;
        var terms=delivery.entrySet().stream().map(e -> new ResourceRequirement(e.getKey(),e.getValue())).toList();
        var exchange=DeliveryInventory.plan(player.getInventory(),terms,0).orElseThrow();
        var next=p.supply(delivery); if(next.state()==ConstructionState.READY && !next.builderRequired()) next=next.start(now(player.server));
        if(!storage.replace(p,next)) return false;
        exchange.apply(player.getInventory()); player.inventoryMenu.broadcastChanges();
        if(player.containerMenu!=player.inventoryMenu) player.containerMenu.broadcastChanges();
        assignReady(player.server,settlementId);
        dev.livingkingdoms.rpg.ContractService.progress(player,dev.livingkingdoms.rpg.domain.Contract.Kind.SUPPLY,1);
        return true;
    }
    public static boolean retry(ServerPlayer player,UUID settlementId,UUID projectId) {
        var storage=ConstructionSavedData.get(player.server); var entry=storage.get(projectId).orElse(null);
        if(entry==null || !entry.project().settlementId().equals(settlementId) || entry.project().state()!=ConstructionState.FAILED
                || !canContribute(player,SettlementSavedData.get(player.server).get(settlementId).orElse(null))) return false;
        if(!storage.replace(entry.project(),entry.project().retry())) return false;
        SITE_RETRY.remove(projectId); assignReady(player.server,settlementId); return true;
    }
    private static boolean canContribute(ServerPlayer player,Settlement settlement) {
        if(settlement==null || !settlement.faction().isAllied() || player.isSpectator() || !player.isAlive() || !player.mayBuild()) return false;
        var pos=player.blockPosition(); return settlement.territory().contains(player.serverLevel().dimension().location().toString(),pos.getX(),pos.getZ());
    }
    public static boolean resolve(MinecraftServer server,UUID projectId,ServerPlayer actor) {
        return resolve(server,projectId,actor,false);
    }
    public static boolean resolve(MinecraftServer server,UUID projectId,ServerPlayer actor,boolean debug) {
        var storage=ConstructionSavedData.get(server); var entry=storage.get(projectId).orElse(null); long now=now(server);
        if(entry==null || entry.project().state()==ConstructionState.COMPLETED || !entry.project().funded()
                || !debug && !entry.project().due(now)) return false;
        if(!debug && entry.project().builderRequired() && !workerEligible(server,entry)) return false;
        var settlements=SettlementSavedData.get(server); var settlement=settlements.get(entry.project().settlementId()).orElseThrow();
        if(!settlement.territory().equals(entry.plan().territory()) || !settlement.faction().isAllied())
            throw new IllegalStateException("Saved construction does not belong to this settlement territory");
        ServerLevel level=level(server,entry);
        if(level==null || !chunksLoaded(level,entry)) {
            if(!entry.project().builderRequired() && entry.project().due(now) && !entry.project().awaitingChunks()) storage.replace(entry.project(),entry.project().defer(now)); return false;
        }
        evacuate(level,entry,null);
        var oldLayout=settlements.layout(settlement.id()).orElseThrow(); var b=entry.plan().buildings().getFirst();
        var description=new SettlementLayoutMetadata.Building(b.module().kind(),b.module().id(),b.origin(),b.rotation(),b.bounds(),b.entrance());
        // A clean exact metadata receipt can be ahead of project data after an interrupted Minecraft save.
        var updated=oldLayout.buildings().contains(description)?oldLayout:oldLayout.append(entry.plan());
        try {
            updated.validate(settlement.territory(),settlement.provenance().origin()==SettlementOrigin.CONVERTED);
            Set<UUID> workers=entry.project().builderId()==null?Set.of():CitizenSavedData.get(server).citizen(entry.project().builderId())
                    .map(c -> Set.of(c.entityId())).orElse(Set.of());
            try(var transaction=ConstructionStages.apply(level,entry,entry.project().visualStage(),4,actor,new HashSet<>(oldLayout.paths()),workers)) {
                if(transaction.result()!=ConstructionStages.Result.APPLIED) {
                    blocked(server,entry,transaction); return false;
                }
                var completed=debug?entry.project().debugComplete(now):entry.project().complete(now);
                boolean award=!debug && completed.builderRequired() && !completed.stageXpAwarded(4);
                if(award) completed=completed.awardStageXp(4);
                settlements.updateLayout(settlement.id(),updated);
                if(!storage.replace(entry.project(),completed)) throw new IllegalStateException("Project changed during completion");
                transaction.commit();
                if(award) reward(server,completed.builderId(),BuilderConfig.XP_COMPLETION.get());
            }
        } catch(RuntimeException failure) {
            settlements.updateLayout(settlement.id(),oldLayout);
            if(entry.project().state()==ConstructionState.BUILDING) storage.replace(entry.project(),entry.project().fail());
            LogUtils.getLogger().warn("Construction {} blocked; blueprint and supplied resources retained: {}",projectId,failure.toString()); return false;
        }
        SITE_RETRY.remove(projectId); REASONS.remove(projectId); OBSTACLES.remove(projectId); ASSISTANTS.remove(projectId);
        if(!debug) dev.livingkingdoms.chronicle.ChronicleService.buildingCompleted(server,settlement.id(),projectId,entry.project().building());
        if(!debug) dev.livingkingdoms.rpg.KingdomProgressService.awardKingdom(server,settlement.id(),dev.livingkingdoms.rpg.domain.KingdomRank.CONSTRUCTION_XP,actor);
        try { ensureNext(level,settlement.id(),actor); }
        catch(RuntimeException failure) { LogUtils.getLogger().warn("Next construction plot unavailable for {}; inspect construction to retry",settlement.id(),failure); }
        dev.livingkingdoms.citizen.CitizenService.ensureInitialized(level,settlements.get(settlement.id()).orElseThrow());
        dev.livingkingdoms.profession.ProfessionService.ensure(level,settlements.get(settlement.id()).orElseThrow());
        // The moment a founding camp becomes a real settlement, one resident takes up the Town Hall guard post.
        if(settlement.lifecycle()==SettlementLifecycle.FOUNDING && settlements.get(settlement.id()).orElseThrow().lifecycle()==SettlementLifecycle.ESTABLISHED)
            dev.livingkingdoms.profession.ProfessionService.ensureStartingGuard(level,settlements.get(settlement.id()).orElseThrow());
        dev.livingkingdoms.quest.persistence.QuestSavedData.get(server).resolveDevelopment(settlement.id(),now);
        for(ServerPlayer player:level.players()) if(canContribute(player,settlement)) {
            if(!debug && entry.project().building()==BuildingKind.WAREHOUSE) dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player,"first_warehouse");
            player.displayClientMessage(Component.translatable("construction.livingkingdoms.completed_in",Component.translatable("construction.livingkingdoms.building."+entry.project().building().name().toLowerCase(Locale.ROOT)),settlement.name()),true);
        }
        assignReady(server,settlement.id());
        return true;
    }
    public static Optional<ConstructionSavedData.Entry> projectForBuilder(MinecraftServer server,UUID citizen) {
        return ConstructionSavedData.get(server).forBuilder(citizen);
    }
    /** Bounded settlement FIFO: unloaded workers wait; ownership is never inferred from an entity scan. */
    public static void assignReady(MinecraftServer server,UUID settlementId) {
        var storage=ConstructionSavedData.get(server);
        var settlement=SettlementSavedData.get(server).get(settlementId).orElse(null);
        if(settlement==null || !settlement.faction().isAllied()) return;
        var jobs=ProfessionSavedData.get(server); var people=CitizenSavedData.get(server);
        for(var entry:storage.readyProjects(settlementId)) {
            if(storage.buildingCount(settlementId)>=ConstructionConfig.ACTIVE_PROJECTS.get()) break;
            if(!entry.project().builderRequired()) {
                storage.replace(entry.project(),entry.project().start(now(server))); continue;
            }
            var level=level(server,entry); if(level==null) continue;
            for(var job:jobs.professions(settlementId)) {
                if(!job.active() || job.type()!=ProfessionType.BUILDER || storage.forBuilder(job.citizenId()).isPresent()) continue;
                var citizen=people.citizen(job.citizenId()).orElse(null);
                var workplace=jobs.building(job.workplaceId()).orElse(null);
                if(citizen==null || citizen.homeId()==null || workplace==null || !workplace.active()
                        || !workplace.supports(ProfessionType.BUILDER) || !(level.getEntity(citizen.entityId()) instanceof Villager v)
                        || !CitizenService.canApply(citizen,v) || !BuilderWork.homeReady(v) || BuilderWork.inDanger(v)) continue;
                if(storage.replace(entry.project(),entry.project().start(now(server),citizen.id()))) {
                    var started=storage.get(entry.project().id()).orElseThrow();
                    placeStage(server,started,started.project(),0,null,false);
                    BuilderWork.control(v); break;
                }
            }
        }
    }
    public static void releaseBuilder(MinecraftServer server,UUID citizen) {
        var storage=ConstructionSavedData.get(server);
        storage.forBuilder(citizen).ifPresent(entry -> { storage.replace(entry.project(),entry.project().releaseBuilder()); SITE_RETRY.remove(entry.project().id()); });
    }
    /** Outside the transformed building body, so finishing cannot entomb the worker or block final placement. */
    public static BlockPos workPoint(ConstructionSavedData.Entry entry) {
        var cached=WORK_POINTS.get(entry.project().id()); if(cached!=null && cached.plan()==entry.plan()) return cached.feet();
        var b=entry.plan().buildings().getFirst(); var desired=ConstructionStages.expected(entry,4);
        var before=entry.plan().before(); var entrance=b.entrance().above();
        // Doorstep paving may raise terrain during final placement. Stand on the saved terrain
        // where both original and finished headroom stay air, rather than inside future paving.
        var point=before.keySet().stream().filter(pos -> !b.bounds().contains(pos.getX(),pos.getZ())
                && before.get(pos).isAir() && desired.get(pos).isAir()
                && before.containsKey(pos.above()) && before.get(pos.above()).isAir() && desired.get(pos.above()).isAir()
                && before.containsKey(pos.below()) && before.get(pos.below()).isSolid() && desired.get(pos.below()).isSolid())
                .min(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(entrance)).thenComparingLong(BlockPos::asLong)).orElse(entrance);
        if(WORK_POINTS.size()>=128) WORK_POINTS.remove(WORK_POINTS.keySet().iterator().next());
        WORK_POINTS.put(entry.project().id(),new WorkPoint(entry.plan(),point)); return point;
    }
    public static boolean contribute(Villager builder) {
        if(!(builder.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        var job=BuilderWork.profile(builder).orElse(null); if(job==null) return false;
        var server=level.getServer(); var entry=projectForBuilder(server,job.citizenId()).orElse(null);
        if(entry==null || !workerEligible(server,entry)) return false;
        if(clearObstacles(level,entry,builder)) { builder.swing(net.minecraft.world.InteractionHand.MAIN_HAND); return true; }
        if(SITE_RETRY.getOrDefault(entry.project().id(),0L)>now(server)) { evacuate(level,entry,builder); return false; }
        var p=entry.project(); long now=now(server); int interval=BuilderConfig.WORK_INTERVAL.get();
        if(now-p.lastWorkAt()<interval) return false;
        // One interval maximum: traveling, unloading, sleeping and server downtime never bank work.
        long units=Math.max(1,Math.round(interval*BuilderPolicy.speedMultiplier(job.level().value(),BuilderConfig.LEVEL_CAP.get(),BuilderConfig.MAX_SPEED_BONUS.get())));
        var next=p.addWork(now,units); if(next.equals(p)) return false;
        int target=next.targetStage();
        if(target>=4) {
            if(!ConstructionSavedData.get(server).replace(p,next)) return false;
            evacuate(level,entry,builder);
            return resolve(server,p.id(),null);
        }
        if(target>p.visualStage()) { evacuate(level,entry,builder); return placeStage(server,entry,next,target,null,true); }
        if(!ConstructionSavedData.get(server).replace(p,next)) return false;
        gradual(level,storageEntry(server,p.id()),(double)next.workTicks()/next.durationTicks(),Set.of(builder.getUUID()));
        return true;
    }
    private static ConstructionSavedData.Entry storageEntry(MinecraftServer server,UUID id) { return ConstructionSavedData.get(server).get(id).orElseThrow(); }

    /**
     * Extra Builders (up to {@link #MAX_ASSISTANTS}) help the settlement's active project: each adds its own
     * work units, so two or three Builders finish proportionally faster. The lead keeps the receipt.
     */
    public static Optional<ConstructionSavedData.Entry> assistProject(MinecraftServer server,UUID citizen) {
        var job=ProfessionSavedData.get(server).profession(citizen).orElse(null);
        if(job==null || !job.active() || job.type()!=ProfessionType.BUILDER || projectForBuilder(server,citizen).isPresent()) return Optional.empty();
        long now=now(server);
        for(var entry:ConstructionSavedData.get(server).projects(job.settlementId())) {
            var p=entry.project();
            if(p.state()!=ConstructionState.BUILDING || !p.builderRequired() || p.builderId()==null || p.builderId().equals(citizen)) continue;
            var helpers=ASSISTANTS.computeIfAbsent(p.id(),ignored -> new LinkedHashMap<>());
            helpers.values().removeIf(at -> now-at>200);
            if(helpers.containsKey(citizen) || helpers.size()<MAX_ASSISTANTS) {
                helpers.put(citizen,now);
                if(ASSISTANTS.size()>256) ASSISTANTS.remove(ASSISTANTS.keySet().iterator().next());
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }
    public static int assistants(UUID project) { return ASSISTANTS.getOrDefault(project,Map.of()).size(); }
    public static boolean assist(Villager helper,ConstructionSavedData.Entry entry) {
        if(!(helper.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        var job=BuilderWork.profile(helper).orElse(null); if(job==null) return false;
        var server=level.getServer(); var current=ConstructionSavedData.get(server).get(entry.project().id()).orElse(null);
        if(current==null || current.project().state()!=ConstructionState.BUILDING || !current.project().builderRequired()) return false;
        var at=workPoint(current); int range=BuilderConfig.WORK_RANGE.get()+2;
        if(helper.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)>range*range || !chunksLoaded(level,current)) return false;
        if(clearObstacles(level,current,helper)) return true;
        var p=current.project();
        long units=Math.max(1,Math.round(BuilderConfig.WORK_INTERVAL.get()*BuilderPolicy.speedMultiplier(job.level().value(),BuilderConfig.LEVEL_CAP.get(),BuilderConfig.MAX_SPEED_BONUS.get())));
        // Helpers stop just short of completion; the lead's next interval finishes and places the building.
        long capped=Math.min(units,Math.max(0,p.durationTicks()-1-p.workTicks()));
        if(capped<1) return false;
        var next=p.assistWork(capped);
        if(next.equals(p) || !ConstructionSavedData.get(server).replace(p,next)) return false;
        if(next.targetStage()<=p.visualStage())
            gradual(level,storageEntry(server,p.id()),(double)next.workTicks()/next.durationTicks(),Set.of(helper.getUUID()));
        return true;
    }

    /** Gradual block placement toward the next stage for both Builder and timed projects. */
    static void gradual(ServerLevel level,ConstructionSavedData.Entry entry,double fraction,Set<UUID> workers) {
        int visual=entry.project().visualStage();
        if(visual<0 || visual>=4) return;
        double sub=Math.max(0,Math.min(1,fraction*4-visual));
        var placed=ConstructionStages.partial(level,entry,visual,sub,6,workers);
        if(placed.isEmpty()) return;
        var first=placed.getFirst(); var state=level.getBlockState(first);
        level.playSound(null,first,state.getSoundType().getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,.8F,.9F+level.random.nextFloat()*.2F);
        for(var pos:placed) level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,level.getBlockState(pos)),
                pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,4,.3,.3,.3,.05);
    }

    /** Timed (Builder-less) projects: reveal stages and gradual blocks from elapsed time. Called from the 20-tick construction tick. */
    public static void advanceTimed(MinecraftServer server,UUID projectId) {
        var entry=ConstructionSavedData.get(server).get(projectId).orElse(null);
        if(entry==null || entry.project().builderRequired() || entry.project().state()!=ConstructionState.BUILDING) return;
        var level=level(server,entry); if(level==null || !chunksLoaded(level,entry)) return;
        if(clearObstacles(level,entry,null)) return;
        long now=now(server); double fraction=entry.project().progress(now);
        int target=(int)Math.min(3,Math.floor(fraction*4));
        if(target>entry.project().visualStage()) { evacuate(level,entry,null); placeStage(server,entry,entry.project(),target,null,false); return; }
        gradual(level,entry,fraction,Set.of());
    }

    /**
     * Removes up to one foreign block per call (three with a pickaxe in the Builder's hand), never containers or
     * other block entities. Remaining obstacles glow red with particles. Returns true when this call did work.
     */
    static boolean clearObstacles(ServerLevel level,ConstructionSavedData.Entry entry,Villager builder) {
        var list=OBSTACLES.get(entry.project().id()); if(list==null || list.isEmpty()) return false;
        int budget=builder!=null && builder.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES)?3:1; boolean worked=false;
        var iterator=list.iterator();
        while(iterator.hasNext() && budget>0) {
            var pos=iterator.next();
            if(!level.hasChunkAt(pos)) continue;
            var state=level.getBlockState(pos);
            if(ConstructionStages.tolerable(state)) { iterator.remove(); continue; }
            if(level.getBlockEntity(pos)!=null || state.getDestroySpeed(level,pos)<0) {
                reason(entry.project().id(),"protected",pos,state.getBlock().getName().getString()); continue;
            }
            // Drops land at the work point, outside the footprint, so they never block the next stage.
            var at=safePoint(level,entry);
            net.minecraft.world.level.block.Block.dropResources(state,level,at,null,builder,net.minecraft.world.item.ItemStack.EMPTY);
            level.destroyBlock(pos,false,builder); iterator.remove(); budget--; worked=true;
        }
        if(list.isEmpty()) { OBSTACLES.remove(entry.project().id()); REASONS.remove(entry.project().id()); SITE_RETRY.remove(entry.project().id()); }
        else highlight(level,list);
        return worked;
    }
    private static void highlight(ServerLevel level,List<BlockPos> positions) {
        var red=new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1F,.1F,.1F),1.6F);
        for(var pos:positions.subList(0,Math.min(16,positions.size())))
            level.sendParticles(red,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,6,.35,.35,.35,0);
    }

    /**
     * Moves items, orbs, animals and residents out of the building volume before a stage is placed, so a
     * chicken or a dropped item can no longer stall construction. Players and hostile mobs are reported;
     * a Builder holding a sword strikes a hostile mob in the way.
     */
    static void evacuate(ServerLevel level,ConstructionSavedData.Entry entry,Villager builder) {
        var box=entry.plan().buildings().getFirst().volume(); var at=safePoint(level,entry);
        // The placement checks every planned position, including paths around the footprint.
        int minX=box.minX(),minY=box.minY(),minZ=box.minZ(),maxX=box.maxX(),maxY=box.maxY(),maxZ=box.maxZ();
        for(var pos:entry.plan().before().keySet()) { minX=Math.min(minX,pos.getX());minY=Math.min(minY,pos.getY());minZ=Math.min(minZ,pos.getZ());
            maxX=Math.max(maxX,pos.getX());maxY=Math.max(maxY,pos.getY());maxZ=Math.max(maxZ,pos.getZ()); }
        var area=new net.minecraft.world.phys.AABB(minX,minY,minZ,maxX+1,maxY+1,maxZ+1);
        var scope=entry.plan().before().keySet();
        for(var entity:level.getEntities((net.minecraft.world.entity.Entity)null,area,e -> !e.isSpectator() && touches(e,scope))) {
            if(entity instanceof ServerPlayer player) { reason(entry.project().id(),"player",player.blockPosition(),player.getGameProfile().getName()); continue; }
            if(entity instanceof net.minecraft.world.entity.monster.Enemy && entity instanceof net.minecraft.world.entity.LivingEntity mob) {
                reason(entry.project().id(),"monster",mob.blockPosition(),mob.getName().getString());
                highlight(level,List.of(mob.blockPosition().above()));
                if(builder!=null && builder.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && builder.distanceToSqr(mob)<64) {
                    builder.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    mob.hurt(level.damageSources().mobAttack(builder),6F);
                }
                continue;
            }
            if(entity==builder) continue;
            // The Mayor is a fixed plaza anchor and is never relocated.
            if(dev.livingkingdoms.npc.NpcIdentity.read(entity).filter(id -> id.role()==dev.livingkingdoms.npc.NpcRole.MAYOR).isPresent()) {
                reason(entry.project().id(),"occupied",entity.blockPosition(),entity.getName().getString()); continue;
            }
            if(entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb
                    || entity instanceof net.minecraft.world.entity.animal.Animal || entity instanceof Villager)
                entity.teleportTo(at.getX()+.5,at.getY(),at.getZ()+.5);
        }
    }

    private static boolean touches(net.minecraft.world.entity.Entity entity,Set<BlockPos> scope) {
        var b=entity.getBoundingBox();
        for(var pos:BlockPos.betweenClosed((int)Math.floor(b.minX),(int)Math.floor(b.minY),(int)Math.floor(b.minZ),(int)Math.floor(b.maxX),(int)Math.floor(b.maxY),(int)Math.floor(b.maxZ)))
            if(scope.contains(pos)) return true;
        return false;
    }
    private static final Map<UUID,BlockPos> SAFE_POINTS=new LinkedHashMap<>();
    /**
     * A standable spot just outside every planned position (footprint and paths), so evacuated entities and
     * dropped obstacle items never land back inside the placement scope. Falls back to the work point.
     */
    static BlockPos safePoint(ServerLevel level,ConstructionSavedData.Entry entry) {
        var cached=SAFE_POINTS.get(entry.project().id());
        if(cached!=null && level.getBlockState(cached).isAir() && level.getBlockState(cached.below()).isSolid()) return cached;
        var scope=entry.plan().before().keySet(); var center=workPoint(entry); BlockPos found=null;
        search:
        for(int radius=1;radius<=10;radius++) for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
            for(int dy=-2;dy<=2;dy++) {
                var feet=center.offset(dx,dy,dz);
                if(!level.hasChunkAt(feet) || scope.contains(feet) || scope.contains(feet.above()) || scope.contains(feet.below())) continue;
                if(level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir() && level.getBlockState(feet.below()).isSolid()) { found=feet; break search; }
            }
        }
        if(found==null) found=center;
        if(SAFE_POINTS.size()>=128) SAFE_POINTS.remove(SAFE_POINTS.keySet().iterator().next());
        SAFE_POINTS.put(entry.project().id(),found); return found;
    }
    private static void reason(UUID project,String key,BlockPos pos,String subject) {
        if(!REASONS.containsKey(project) && REASONS.size()>=256) REASONS.remove(REASONS.keySet().iterator().next());
        REASONS.put(project,new Reason("construction.livingkingdoms.reason."+key,List.of(subject,pos.getX()+" "+pos.getY()+" "+pos.getZ())));
    }
    public static Optional<Reason> reason(UUID project) { return Optional.ofNullable(REASONS.get(project)); }
    public static List<BlockPos> obstacles(UUID project) { return List.copyOf(OBSTACLES.getOrDefault(project,List.of())); }
    private static boolean workerEligible(MinecraftServer server,ConstructionSavedData.Entry entry) {
        var p=entry.project(); if(p.builderId()==null) return false;
        var level=level(server,entry); if(level==null) return false;
        var citizen=CitizenSavedData.get(server).citizen(p.builderId()).orElse(null);
        if(citizen==null || !(level.getEntity(citizen.entityId()) instanceof Villager v)) return false;
        var job=BuilderWork.profile(v).orElse(null); var at=workPoint(entry); int range=BuilderConfig.WORK_RANGE.get();
        var workplace=job==null?null:ProfessionSavedData.get(server).building(job.workplaceId()).orElse(null);
        return job!=null && job.citizenId().equals(p.builderId()) && job.settlementId().equals(p.settlementId())
                && citizen.settlementId().equals(p.settlementId()) && workplace!=null && workplace.active()
                && workplace.settlementId().equals(p.settlementId()) && workplace.supports(ProfessionType.BUILDER)
                && BuilderWork.homeReady(v) && !BuilderWork.inDanger(v)
                && !v.isNoAi() && !v.isSleeping() && !v.isTrading() && !v.isPassenger() && !v.isLeashed()
                && v.distanceToSqr(at.getX()+.5,at.getY(),at.getZ()+.5)<=range*range
                && chunksLoaded(level,entry);
    }
    public static boolean isLoaded(MinecraftServer server,ConstructionSavedData.Entry entry) {
        var level=level(server,entry); return level!=null && chunksLoaded(level,entry);
    }
    private static boolean chunksLoaded(ServerLevel level,ConstructionSavedData.Entry entry) {
        var cached=CHUNKS.get(entry.project().id());
        if(cached==null || cached.plan()!=entry.plan()) {
            Set<Long> chunks=new HashSet<>();
            for(var pos:entry.plan().before().keySet()) chunks.add(net.minecraft.world.level.ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4));
            cached=new LoadedPlan(entry.plan(),Set.copyOf(chunks));
            if(CHUNKS.size()>=128) CHUNKS.remove(CHUNKS.keySet().iterator().next());
            CHUNKS.put(entry.project().id(),cached);
        }
        for(long packed:cached.chunks()) {
            var chunk=new net.minecraft.world.level.ChunkPos(packed);
            if(!level.getChunkSource().hasChunk(chunk.x,chunk.z)) return false;
        }
        return true;
    }
    private static boolean placeStage(MinecraftServer server,ConstructionSavedData.Entry entry,ConstructionProject next,int target,ServerPlayer actor,boolean xp) {
        var level=level(server,entry); if(level==null || target<=entry.project().visualStage()) return false;
        try(var transaction=ConstructionStages.apply(level,entry,entry.project().visualStage(),target,actor)) {
            if(transaction.result()!=ConstructionStages.Result.APPLIED) { blocked(server,entry,transaction); return false; }
            if(xp) next=next.stagePlaced(target);
            else if(actor==null) next=next.stagePlaced(target);
            else next=next.debugAdvance(now(server),target);
            int reward=0;
            if(xp) for(int stage=1;stage<=target;stage++) if(!next.stageXpAwarded(stage)) {
                next=next.awardStageXp(stage); reward+=BuilderConfig.XP_PER_STAGE.get();
            }
            if(!ConstructionSavedData.get(server).replace(entry.project(),next)) return false;
            transaction.commit(); if(reward>0) reward(server,next.builderId(),reward); SITE_RETRY.remove(next.id()); REASONS.remove(next.id()); return true;
        }
    }
    private static void reward(MinecraftServer server,UUID citizen,int xp) {
        if(citizen==null) return;
        var jobs=ProfessionSavedData.get(server); jobs.profession(citizen).ifPresent(p -> jobs.builderExperience(p,xp,BuilderConfig.LEVEL_CAP.get(),BuilderConfig.XP_STEP.get()));
    }
    private static void blocked(MinecraftServer server,ConstructionSavedData.Entry entry,ConstructionStages.Transaction transaction) {
        var p=entry.project(); var storage=ConstructionSavedData.get(server);
        if(transaction.result()==ConstructionStages.Result.UNLOADED) return;
        if(!transaction.obstacles().isEmpty()) {
            if(!OBSTACLES.containsKey(p.id()) && OBSTACLES.size()>=256) OBSTACLES.remove(OBSTACLES.keySet().iterator().next());
            OBSTACLES.put(p.id(),new ArrayList<>(transaction.obstacles()));
            var first=transaction.obstacles().getFirst(); var level=level(server,entry);
            reason(p.id(),"obstacle",first,level==null?"?":level.getBlockState(first).getBlock().getName().getString());
            if(p.builderRequired()) { if(SITE_RETRY.size()>=1024) SITE_RETRY.remove(SITE_RETRY.keySet().iterator().next()); SITE_RETRY.put(p.id(),now(server)+20); }
            else { if(p.awaitingChunks()) storage.replace(p,p.resume()); storage.scheduleSiteRetry(p.id(),now(server)); }
            return;
        }
        if(transaction.occupied()) {
            if(!REASONS.containsKey(p.id())) reason(p.id(),"occupied",entry.plan().buildings().getFirst().origin(),"");
            if(p.builderRequired()) { if(SITE_RETRY.size()>=1024) SITE_RETRY.remove(SITE_RETRY.keySet().iterator().next()); SITE_RETRY.put(p.id(),now(server)+100); }
            else { if(p.awaitingChunks()) storage.replace(p,p.resume()); storage.scheduleSiteRetry(p.id(),now(server)); }
        } else if(p.state()==ConstructionState.BUILDING) {
            storage.replace(p,p.fail());
            reason(p.id(),"failed",entry.plan().buildings().getFirst().origin(),String.valueOf(transaction.failure().map(Throwable::getMessage).orElse("")));
            LogUtils.getLogger().warn("Construction {} retained for safe retry: {}",p.id(),transaction.failure());
        }
    }
    public static boolean debugAdvance(MinecraftServer server,UUID projectId,int stage,ServerPlayer actor) {
        var storage=ConstructionSavedData.get(server); var entry=storage.get(projectId).orElse(null);
        if(entry==null || stage<0 || stage>4 || entry.project().state()==ConstructionState.COMPLETED) return false;
        var p=entry.project();
        if(p.state()==ConstructionState.WAITING_FOR_RESOURCES) {
            Map<ResourceKind,Integer> materials=new EnumMap<>(ResourceKind.class);
            for(var k:p.required().keySet()) if(p.missing(k)>0) materials.put(k,p.missing(k));
            var supplied=p.supply(materials); if(!supplied.builderRequired()) supplied=supplied.start(now(server));
            if(!storage.replace(p,supplied)) return false;
            entry=storage.get(projectId).orElseThrow(); p=entry.project();
        }
        if(!p.funded()) return false;
        if(stage==4) return resolve(server,projectId,actor,true);
        return placeStage(server,entry,p,stage,actor,false);
    }
    /** Construction supply demand augments existing shortage quests; no quest schema changes. */
    public static Map<ResourceKind,Integer> shortageWeights(MinecraftServer server,UUID settlement) {
        Map<ResourceKind,Integer> weights=new EnumMap<>(ResourceKind.class);
        for(var entry:ConstructionSavedData.get(server).projects(settlement)) if(entry.project().state()==ConstructionState.WAITING_FOR_RESOURCES)
            entry.project().required().keySet().forEach(k -> { if(entry.project().missing(k)>0) weights.put(k,10); });
        return Map.copyOf(weights);
    }
    private static ServerLevel level(MinecraftServer server,ConstructionSavedData.Entry entry) {
        return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(entry.plan().territory().dimension())));
    }
    public static void rollbackFounding(ServerLevel level,UUID settlement) {
        var storage=ConstructionSavedData.get(level.getServer());
        for(var e:storage.projects(settlement)) if(level.hasChunkAt(e.marker()) && level.getBlockState(e.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER))
            level.setBlock(e.marker(),e.plan().before().get(e.marker()),18);
        storage.rollback(settlement);
    }
    public static long now(MinecraftServer server) { return Math.max(0,server.overworld().getGameTime()); }
}

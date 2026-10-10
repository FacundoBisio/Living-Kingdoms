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
        var kind=hall ? BuildingKind.HOUSE : BuildingKind.TOWN_HALL;
        var plan=new SettlementGenerator().planBuildingAddition(level,settlementId,kind,new GenerationDiagnostics());
        if(plan.isEmpty()) return false;
        reserve(level,settlementId,plan.orElseThrow(),actor); return true;
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
    /** Also reusable by later expansion policies; the initial chain is the only current caller. */
    public static ConstructionSavedData.Entry reserve(ServerLevel level,UUID settlementId,SettlementLayout plan,ServerPlayer actor) {
        var kind=plan.buildings().getFirst().module().kind();
        return reserve(level,settlementId,plan,ConstructionConfig.cost(kind),ConstructionConfig.duration(kind),actor);
    }
    public static ConstructionSavedData.Entry reserve(ServerLevel level,UUID settlementId,SettlementLayout plan,
            Map<ResourceKind,Integer> requirements,long duration,ServerPlayer actor) {
        var settlements=SettlementSavedData.get(level.getServer()); var settlement=settlements.get(settlementId).orElseThrow();
        var metadata=settlements.layout(settlementId).orElseThrow();
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
        assignReady(player.server,settlementId); return true;
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
        SITE_RETRY.remove(projectId);
        try { ensureNext(level,settlement.id(),actor); }
        catch(RuntimeException failure) { LogUtils.getLogger().warn("Next construction plot unavailable for {}; inspect construction to retry",settlement.id(),failure); }
        dev.livingkingdoms.citizen.CitizenService.ensureInitialized(level,settlements.get(settlement.id()).orElseThrow());
        dev.livingkingdoms.profession.ProfessionService.ensure(level,settlements.get(settlement.id()).orElseThrow());
        for(ServerPlayer player:level.players()) if(canContribute(player,settlement))
            player.displayClientMessage(Component.translatable("construction.livingkingdoms.completed_in",Component.translatable("construction.livingkingdoms.building."+entry.project().building().name().toLowerCase(Locale.ROOT)),settlement.name()),true);
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
        if(entry==null || !workerEligible(server,entry) || SITE_RETRY.getOrDefault(entry.project().id(),0L)>now(server)) return false;
        var p=entry.project(); long now=now(server); int interval=BuilderConfig.WORK_INTERVAL.get();
        if(now-p.lastWorkAt()<interval) return false;
        // One interval maximum: traveling, unloading, sleeping and server downtime never bank work.
        long units=Math.max(1,Math.round(interval*BuilderPolicy.speedMultiplier(job.level().value(),BuilderConfig.LEVEL_CAP.get(),BuilderConfig.MAX_SPEED_BONUS.get())));
        var next=p.addWork(now,units); if(next.equals(p)) return false;
        int target=next.targetStage();
        if(target>=4) {
            if(!ConstructionSavedData.get(server).replace(p,next)) return false;
            return resolve(server,p.id(),null);
        }
        if(target>p.visualStage()) return placeStage(server,entry,next,target,null,true);
        return ConstructionSavedData.get(server).replace(p,next);
    }
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
            transaction.commit(); if(reward>0) reward(server,next.builderId(),reward); SITE_RETRY.remove(next.id()); return true;
        }
    }
    private static void reward(MinecraftServer server,UUID citizen,int xp) {
        if(citizen==null) return;
        var jobs=ProfessionSavedData.get(server); jobs.profession(citizen).ifPresent(p -> jobs.builderExperience(p,xp,BuilderConfig.LEVEL_CAP.get(),BuilderConfig.XP_STEP.get()));
    }
    private static void blocked(MinecraftServer server,ConstructionSavedData.Entry entry,ConstructionStages.Transaction transaction) {
        var p=entry.project(); var storage=ConstructionSavedData.get(server);
        if(transaction.result()==ConstructionStages.Result.UNLOADED) return;
        if(transaction.occupied()) {
            if(p.builderRequired()) { if(SITE_RETRY.size()>=1024) SITE_RETRY.remove(SITE_RETRY.keySet().iterator().next()); SITE_RETRY.put(p.id(),now(server)+100); }
            else { if(p.awaitingChunks()) storage.replace(p,p.resume()); storage.scheduleSiteRetry(p.id(),now(server)); }
        } else if(p.state()==ConstructionState.BUILDING) {
            storage.replace(p,p.fail());
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

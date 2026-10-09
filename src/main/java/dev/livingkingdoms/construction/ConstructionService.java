package dev.livingkingdoms.construction;

import com.mojang.logging.LogUtils;
import dev.livingkingdoms.block.KingdomBlocks;
import dev.livingkingdoms.config.ConstructionConfig;
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
import java.util.*;

/** All mutations are synchronous on the server thread. Clients never supply counts, costs, time or plots. */
public final class ConstructionService {
    private ConstructionService() {}
    public static Optional<ConstructionSavedData.Entry> current(MinecraftServer server,UUID settlement) {
        return ConstructionSavedData.get(server).projects(settlement).stream().filter(e -> e.project().state()!=ConstructionState.COMPLETED).findFirst();
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
    private static boolean planFunctional(ServerLevel level,UUID settlementId,ServerPlayer actor,BuildingKind kind) {
        var data=SettlementSavedData.get(level.getServer()); var settlement=data.get(settlementId).orElse(null);
        if(actor==null || !canContribute(actor,settlement) || settlement.lifecycle()!=SettlementLifecycle.ESTABLISHED
                || current(level.getServer(),settlementId).isPresent()) return false;
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
        var p=ConstructionProject.planned(settlementId,b.module().kind(),new ConstructionProject.Plot(b.origin().getX(),b.origin().getY(),b.origin().getZ(),
                ConstructionProject.Orientation.valueOf(b.rotation().name())),requirements,duration,now).waitForResources();
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
        var next=p.supply(delivery); if(next.state()==ConstructionState.READY) next=next.start(now(player.server));
        if(!storage.replace(p,next)) return false;
        exchange.apply(player.getInventory()); player.inventoryMenu.broadcastChanges();
        if(player.containerMenu!=player.inventoryMenu) player.containerMenu.broadcastChanges(); return true;
    }
    public static boolean retry(ServerPlayer player,UUID settlementId,UUID projectId) {
        var storage=ConstructionSavedData.get(player.server); var entry=storage.get(projectId).orElse(null);
        if(entry==null || !entry.project().settlementId().equals(settlementId) || entry.project().state()!=ConstructionState.FAILED
                || !canContribute(player,SettlementSavedData.get(player.server).get(settlementId).orElse(null))) return false;
        return storage.replace(entry.project(),entry.project().retry());
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
        if(entry==null || entry.project().state()!=ConstructionState.BUILDING || !debug && !entry.project().due(now)) return false;
        var settlements=SettlementSavedData.get(server); var settlement=settlements.get(entry.project().settlementId()).orElseThrow();
        if(!settlement.territory().equals(entry.plan().territory()) || !settlement.faction().isAllied())
            throw new IllegalStateException("Saved construction does not belong to this settlement territory");
        ServerLevel level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(entry.plan().territory().dimension())));
        if(level==null || entry.plan().before().keySet().stream().anyMatch(pos -> !level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4))) {
            if(entry.project().due(now) && !entry.project().awaitingChunks()) storage.replace(entry.project(),entry.project().defer(now)); return false;
        }
        var oldLayout=settlements.layout(settlement.id()).orElseThrow(); var updated=oldLayout.append(entry.plan());
        var markerState=level.getBlockState(entry.marker()); boolean ownsMarker=markerState.is(KingdomBlocks.CONSTRUCTION_MARKER);
        var snapshot=EstablishmentPlacementEvents.capture(level,entry.plan().before().keySet());
        try {
            updated.validate(settlement.territory(),settlement.provenance().origin()==SettlementOrigin.CONVERTED);
            if(actor!=null && entry.plan().before().keySet().stream().anyMatch(pos -> !level.mayInteract(actor,pos))) throw new EstablishmentPlacementEvents.Rejected();
            // Remove only our saved marker. A replacement block is an obstacle and must never be erased.
            if(ownsMarker) level.setBlock(entry.marker(),entry.plan().before().get(entry.marker()),18);
            try(var transaction=SettlementPlacement.apply(level,entry.plan())) {
                EstablishmentPlacementEvents.validate(actor,snapshot);
                settlements.updateLayout(settlement.id(),updated);
                if(!storage.replace(entry.project(),debug?entry.project().debugComplete(now):entry.project().complete(now))) throw new IllegalStateException("Project changed during completion");
                transaction.commit();
            }
        } catch(RuntimeException failure) {
            settlements.updateLayout(settlement.id(),oldLayout);
            if(ownsMarker) level.setBlock(entry.marker(),markerState,18);
            if(failure instanceof SettlementPlacement.Occupied) {
                if(entry.project().awaitingChunks()) storage.replace(entry.project(),entry.project().resume());
                storage.scheduleSiteRetry(projectId,now); // Retry after five seconds, never before the saved deadline.
                return false;
            }
            storage.replace(entry.project(),entry.project().fail());
            LogUtils.getLogger().warn("Construction {} blocked; blueprint and supplied resources retained: {}",projectId,failure.toString()); return false;
        }
        try { ensureNext(level,settlement.id(),actor); }
        catch(RuntimeException failure) { LogUtils.getLogger().warn("Next construction plot unavailable for {}; inspect construction to retry",settlement.id(),failure); }
        dev.livingkingdoms.citizen.CitizenService.ensureInitialized(level,settlements.get(settlement.id()).orElseThrow());
        dev.livingkingdoms.profession.ProfessionService.ensure(level,settlements.get(settlement.id()).orElseThrow());
        for(ServerPlayer player:level.players()) if(canContribute(player,settlement))
            player.displayClientMessage(Component.translatable("construction.livingkingdoms.completed",Component.translatable("construction.livingkingdoms.building."+entry.project().building().name().toLowerCase(Locale.ROOT))),true);
        return true;
    }
    public static void rollbackFounding(ServerLevel level,UUID settlement) {
        var storage=ConstructionSavedData.get(level.getServer());
        for(var e:storage.projects(settlement)) if(level.hasChunkAt(e.marker()) && level.getBlockState(e.marker()).is(KingdomBlocks.CONSTRUCTION_MARKER))
            level.setBlock(e.marker(),e.plan().before().get(e.marker()),18);
        storage.rollback(settlement);
    }
    public static long now(MinecraftServer server) { return Math.max(0,server.overworld().getGameTime()); }
}

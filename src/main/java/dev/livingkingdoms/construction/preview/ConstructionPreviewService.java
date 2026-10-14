package dev.livingkingdoms.construction.preview;

import dev.livingkingdoms.config.ConstructionConfig;
import dev.livingkingdoms.construction.ConstructionService;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.settlement.SettlementGenerator;
import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Server-owned short-lived plans. Survey/update never writes world blocks, funds or project records. */
public final class ConstructionPreviewService {
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    private record Session(PreviewSession capability, UUID settlement, BuildingKind kind, BuildingCatalog catalog,
                           BlockPos origin, Rotation rotation, long updated) {}
    private ConstructionPreviewService() {}
    public static boolean supported(BuildingKind kind) {
        return switch (kind) {
            case HOUSE,HOUSE_VARIANT,HOUSE_THIRD,FARM,BARRACKS,WATCHTOWER,WAREHOUSE,LUMBERJACK_HUT,MINE,BLACKSMITH,MARKET -> true;
            default -> false;
        };
    }
    public static boolean eligible(ServerPlayer player, UUID settlementId, BuildingKind kind) {
        var data = SettlementSavedData.get(player.server);
        var settlement = data.get(settlementId).orElse(null);
        if (!supported(kind) || settlement == null || !dev.livingkingdoms.economy.KingdomEconomy.authorized(player,settlementId)
                || settlement.lifecycle() != SettlementLifecycle.ESTABLISHED || !player.isAlive()
                || player.isSpectator() || !player.mayBuild()
                || !settlement.territory().contains(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ())
                || !ConstructionService.canPlan(player.server, settlementId)) return false;
        return !(kind == BuildingKind.BARRACKS || kind == BuildingKind.WATCHTOWER)
                || !(ConstructionService.planned(player.server,settlementId,kind)
                    || data.layout(settlementId).stream().flatMap(m -> m.buildings().stream()).anyMatch(b -> b.kind() == kind));
    }
    /** Call only from a verified local mayor UI session; this method is not a public C2S endpoint. */
    public static boolean begin(ServerPlayer player, UUID settlementId, BuildingKind kind) {
        if (!eligible(player,settlementId,kind)) return false;
        long now = ConstructionService.now(player.server);
        // A UI spammer cannot repeatedly trigger automatic plot search in the same second.
        var previous = SESSIONS.get(player);
        if (previous != null && now - previous.updated < 20) return false;
        try {
            var metadata = metadata(player,settlementId);
            if (metadata.isEmpty()) return false;
            var catalog = BuildingCatalog.load(player.serverLevel(),metadata.get().style());
            var data = SettlementSavedData.get(player.server);
            var occupied = new ArrayList<>(metadata.get().buildings().stream().map(SettlementLayoutMetadata.Building::bounds).toList());
            var storage = ConstructionSavedData.get(player.server); occupied.addAll(storage.reservations(settlementId));
            var paths = new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
            metadata.get().paths().forEach(pos -> paths.put(pos,Blocks.DIRT_PATH.defaultBlockState()));
            storage.projects(settlementId).stream().filter(e -> e.project().state() != ConstructionState.COMPLETED).forEach(e -> paths.putAll(e.plan().pathBlocks()));
            var plan = new SettlementLayoutPlanner().planAddition(player.serverLevel(),data.get(settlementId).orElseThrow().territory(),
                    catalog,kind,occupied,metadata.get().ports(),paths,new GenerationDiagnostics());
            if (plan.isEmpty() || plan.get().before().keySet().stream().anyMatch(pos -> !player.serverLevel().mayInteract(player,pos))) return false;
            var b = plan.get().buildings().getFirst();
            var session = new Session(new PreviewSession(UUID.randomUUID(),player.getUUID(),player.level().dimension().location().toString(),
                    now + PreviewSession.LIFETIME),settlementId,kind,
                    catalog,b.origin(),b.rotation(),now);
            if (SESSIONS.size() >= 256) SESSIONS.remove(SESSIONS.keySet().iterator().next());
            SESSIONS.put(player,session);
            send(player,session,plan.get(),true);
            return true;
        } catch (RuntimeException rejected) { return false; }
    }
    /** Fresh native plot + road planner rechecks loaded chunks, slopes, entities and protected block edits. */
    public static Optional<SettlementLayout> validate(ServerPlayer player, UUID id, BuildingKind kind, BuildingCatalog catalog,
                                                     BlockPos origin, Rotation rotation) {
        if (!eligible(player,id,kind)) return Optional.empty();
        var data = SettlementSavedData.get(player.server); var settlement = data.get(id).orElseThrow();
        var metadata = metadata(player,id).orElse(null);
        if (metadata == null) return Optional.empty();
        var occupied = new ArrayList<>(metadata.buildings().stream().map(SettlementLayoutMetadata.Building::bounds).toList());
        var storage = ConstructionSavedData.get(player.server); occupied.addAll(storage.reservations(id));
        var paths = new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        metadata.paths().forEach(pos -> paths.put(pos,Blocks.DIRT_PATH.defaultBlockState()));
        storage.projects(id).stream().filter(e -> e.project().state() != ConstructionState.COMPLETED).forEach(e -> paths.putAll(e.plan().pathBlocks()));
        var plan = new SettlementLayoutPlanner().planAdditionAt(player.serverLevel(),settlement.territory(),catalog,kind,
                occupied,metadata.ports(),paths,origin,rotation,new GenerationDiagnostics());
        if (plan.isPresent() && plan.get().before().keySet().stream().anyMatch(pos ->
                !player.serverLevel().hasChunkAt(pos) || !player.serverLevel().mayInteract(player,pos))) return Optional.empty();
        return plan;
    }
    public static boolean handle(ServerPlayer player, PreviewPayloads.Request request) {
        var session = SESSIONS.get(player); long now = ConstructionService.now(player.server);
        if (session == null || !session.capability.accepts(request.session(),player.getUUID(),player.level().dimension().location().toString(),now)) {
            // Never let a forged token cancel somebody else's live capability.
            if (session != null && session.capability.token().equals(request.session())) close(player);
            return false;
        }
        if (request.action() == PreviewPayloads.Action.CANCEL) { close(player); return true; }
        if (!eligible(player,session.settlement,session.kind)) { close(player); return false; }
        if (request.action() == PreviewPayloads.Action.CONFIRM) {
            // Consume before validation/reservation: duplicate packets cannot create a second project.
            SESSIONS.remove(player);
            boolean created = false;
            try {
                var plan = validate(player,session.settlement,session.kind,session.catalog,session.origin,session.rotation);
                if (plan.isPresent() && plan.get().buildings().getFirst().origin().equals(session.origin)) {
                    ConstructionService.reserveWithMetadata(player.serverLevel(),session.settlement,plan.get(),
                            ConstructionConfig.cost(session.kind),ConstructionConfig.duration(session.kind),player,
                            metadata(player,session.settlement).orElseThrow());
                    created = true;
                }
            } catch (RuntimeException rejected) { /* The normal reservation transaction rolls back protected marker edits. */ }
            PacketDistributor.sendToPlayer(player,new PreviewPayloads.Snapshot(new CompoundTag()));
            player.displayClientMessage(Component.translatable(created ? "preview.livingkingdoms.created" : "preview.livingkingdoms.rejected"),true);
            return created;
        }
        if (now - session.updated < 10) return false;
        var origin = request.action() == PreviewPayloads.Action.MOVE ? request.origin() : session.origin;
        if (request.action() == PreviewPayloads.Action.MOVE && player.blockPosition().distSqr(origin) > 64 * 64) return false;
        var rotation = request.rotation();
        var next = new Session(session.capability,session.settlement,session.kind,session.catalog,origin,rotation,now);
        Optional<SettlementLayout> plan;
        try { plan = validate(player,next.settlement,next.kind,next.catalog,origin,rotation); }
        catch (RuntimeException rejected) { plan = Optional.empty(); }
        if (plan.isPresent()) next = new Session(next.capability,next.settlement,next.kind,next.catalog,
                plan.get().buildings().getFirst().origin(),rotation,now);
        SESSIONS.put(player,next); send(player,next,plan.orElse(null),plan.isPresent()); return true;
    }
    private static Optional<SettlementLayoutMetadata> metadata(ServerPlayer player, UUID settlementId) {
        var data = SettlementSavedData.get(player.server);
        var stored = data.layout(settlementId); if (stored.isPresent()) return stored;
        var settlement = data.get(settlementId).orElseThrow();
        if (settlement.provenance().origin() != dev.livingkingdoms.settlement.domain.SettlementOrigin.CONVERTED) return Optional.empty();
        var territory = settlement.territory(); var marker = new BlockPos(territory.x(),territory.y(),territory.z()); var level = player.serverLevel();
        var ports = java.util.stream.Stream.of(marker.east().below(),marker.west().below(),marker.north().below(),marker.south().below())
                .filter(pos -> level.hasChunkAt(pos) && level.getBlockState(pos).isSolid()
                        && level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.above(2)).isAir()).toList();
        return ports.isEmpty() ? Optional.empty() : Optional.of(new SettlementLayoutMetadata(ArchitectureStyle.at(level,marker),List.of(),ports,List.of()));
    }
    private static void close(ServerPlayer player) {
        SESSIONS.remove(player); PacketDistributor.sendToPlayer(player,new PreviewPayloads.Snapshot(new CompoundTag()));
    }
    private static void send(ServerPlayer player, Session session, SettlementLayout plan, boolean valid) {
        var module = session.catalog.get(session.kind); var size = module.rotatedSize(session.rotation);
        var b = plan == null ? new SettlementLayout.Building(module,session.origin,session.rotation,
                new PlotBounds(session.origin.getX(),session.origin.getZ(),session.origin.getX()+size.getX()-1,session.origin.getZ()+size.getZ()-1),
                module.worldPosition(module.entrance(),session.origin,session.rotation),List.of()) : plan.buildings().getFirst();
        var tag = new CompoundTag(); tag.putUUID("session",session.capability.token()); tag.putBoolean("valid",valid);
        tag.putString("dimension",session.capability.dimension()); tag.putString("kind",session.kind.name());
        tag.putInt("ticks",(int)Math.max(0,session.capability.expiresAt()-ConstructionService.now(player.server)));
        tag.putLong("origin",b.origin().asLong()); tag.putLong("high",b.high().asLong()); tag.putLong("entry",b.entrance().asLong());
        tag.putString("rotation",b.rotation().name());
        var blocks = new ListTag();
        for (var cell : PreviewGeometry.cells(b)) {
            var block = new CompoundTag(); block.putLong("pos",cell.position().asLong()); block.putInt("state",Block.getId(cell.state())); blocks.add(block);
        }
        tag.put("blocks",blocks); tag.putBoolean("simplified",module.blocks().stream().filter(c -> !c.state().isAir()).count() > PreviewGeometry.MAX_BLOCKS);
        var costs = new CompoundTag(); ConstructionConfig.cost(session.kind).forEach((kind,count) -> costs.putInt(kind.name(),count)); tag.put("cost",costs);
        tag.putLong("duration",ConstructionConfig.duration(session.kind));
        PacketDistributor.sendToPlayer(player,new PreviewPayloads.Snapshot(tag));
    }
}

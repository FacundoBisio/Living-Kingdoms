package dev.livingkingdoms.ui;

import dev.livingkingdoms.economy.*;
import dev.livingkingdoms.profession.*;
import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** On-demand administrative snapshots; every mutation rechecks the economic revision and local capability. */
public final class ManagementService {
    private record Session(UUID token,UUID kingdom,String dimension,BlockPos anchor,long opened,Set<UUID> targets,UUID focus) {
        Session(UUID token,UUID kingdom,String dimension,BlockPos anchor,long opened,Set<UUID> targets) { this(token,kingdom,dimension,anchor,opened,targets,null); }
    }
    private static final Map<ServerPlayer,Session> SESSIONS=new WeakHashMap<>();
    private ManagementService() {}
    public static boolean open(ServerPlayer player,UUID kingdom) { return open(player,kingdom,null); }
    /** Opens management focused on one building (its ledger desk). */
    public static boolean open(ServerPlayer player,UUID kingdom,UUID focus) {
        if(!KingdomEconomy.authorized(player,kingdom)) return false;
        VillageUiService.dismiss(player);QuestJournalService.dismiss(player);dev.livingkingdoms.commerce.CommerceService.dismiss(player);
        SettlementSavedData.get(player.server).get(kingdom).ifPresent(s->dev.livingkingdoms.economy.BuildingHearts.ensureThrottled(player.serverLevel(),s));
        var session=new Session(UUID.randomUUID(),kingdom,player.level().dimension().location().toString(),player.blockPosition().immutable(),KingdomEconomy.now(player.server),Set.of(),focus);
        SESSIONS.put(player,session);send(player,session,null);return true;
    }
    public static Optional<CompoundTag> snapshot(ServerPlayer player) {var s=SESSIONS.get(player);return s==null?Optional.empty():Optional.of(view(player,s,null));}
    public static void dismiss(ServerPlayer player) {SESSIONS.remove(player);}
    public static boolean handle(ServerPlayer player,ManagementPayloads.Request request) {
        var session=SESSIONS.get(player);if(session==null || !session.token.equals(request.session())) return false;
        if(request.action()==ManagementPayloads.Action.CLOSE) {dismiss(player);return true;}
        if(!KingdomEconomy.authorized(player,session.kingdom) || !session.dimension.equals(player.level().dimension().location().toString())
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.anchor))>64 || KingdomEconomy.now(player.server)-session.opened>12000) {
            dismiss(player);var closed=new CompoundTag();closed.putBoolean("closed",true);closed.putUUID("session",session.token);PacketDistributor.sendToPlayer(player,new ManagementPayloads.Snapshot(closed));return false;
        }
        if(request.action()==ManagementPayloads.Action.BACK) {dismiss(player);return VillageUiService.openShortcut(player);}
        if(request.action()==ManagementPayloads.Action.REFRESH) {send(player,session,null);return true;}
        if(request.revision()!=KingdomEconomy.ensure(player.server,session.kingdom).revision()) {send(player,session,"management.livingkingdoms.stale");return false;}
        if((request.action()==ManagementPayloads.Action.UPGRADE || request.action()==ManagementPayloads.Action.REPAIR || request.action()==ManagementPayloads.Action.EQUIP
                || request.action()==ManagementPayloads.Action.HIRE || request.action()==ManagementPayloads.Action.ARCHER)
                && !session.targets.contains(request.target())) return false;
        boolean result=switch(request.action()) {
            case UPGRADE -> BuildingDevelopment.upgrade(player,session.kingdom,request.target());
            case REPAIR -> BuildingDevelopment.repair(player,session.kingdom,request.target());
            case EQUIP -> GuardEquipment.forge(player,session.kingdom,request.target());
            case REPAIR_HELD -> EconomicWork.repairHeld(player,session.kingdom);
            case EXPAND -> BuildingDevelopment.expand(player,session.kingdom);
            case FORTIFY -> dev.livingkingdoms.rpg.WallService.fortify(player,session.kingdom);
            case DEPOSIT_ALL -> dev.livingkingdoms.economy.WarehouseService.depositAll(player,session.kingdom)>0;
            case HIRE -> dev.livingkingdoms.economy.TavernService.hire(player,session.kingdom,request.target());
            case ARCHER -> toggleArcher(player,session.kingdom,request.target());
            default -> false;
        };
        String notice=result?"management.livingkingdoms.success":"management.livingkingdoms.rejected";
        if(!result && request.action()==ManagementPayloads.Action.UPGRADE) notice="management.livingkingdoms.upgrade_missing";
        send(player,session,notice);return result;
    }
    private static void send(ServerPlayer player,Session session,String notice) {PacketDistributor.sendToPlayer(player,new ManagementPayloads.Snapshot(view(player,session,notice)));}
    private static CompoundTag view(ServerPlayer player,Session session,String notice) {
        var realm=SettlementSavedData.get(player.server).get(session.kingdom).orElseThrow();ProfessionService.ensure(player.serverLevel(),realm);
        var account=KingdomEconomy.ensure(player.server,session.kingdom);var jobs=ProfessionSavedData.get(player.server);var rows=new ListTag();var ids=new HashSet<UUID>();
        for(var building:jobs.buildings(session.kingdom)) if(building.active()) {
            var row=new CompoundTag();row.putUUID("id",building.id());row.putString("kind","building");row.putString("name","construction.livingkingdoms.building."+building.kind().name().toLowerCase(Locale.ROOT));
            int level=BuildingDevelopment.level(player.server,session.kingdom,building.id());row.putInt("level",level);row.putInt("workers",jobs.workers(building.id()));row.putInt("slots",building.workplaceSlots());
            row.putInt("x",building.origin().getX());row.putInt("z",building.origin().getZ());row.putBoolean("upgrade",BuildingDevelopment.upgradeable(building.kind()) && level<Math.min(3,dev.livingkingdoms.config.EconomyConfig.MAX_BUILDING_LEVEL.get()));
            CitizenSavedData.get(player.server).housing(building.id()).ifPresent(home->row.putInt("housing",home.capacity()));
            row.putString("benefit","management.livingkingdoms.benefit."+building.kind().name().toLowerCase(Locale.ROOT));row.putBoolean("repair",!account.repairOrders().contains(building.id()) && !building.template().getPath().equals("allied/converted/plaza"));
            row.putBoolean("repair_pending",account.repairOrders().contains(building.id()));var costs=new ListTag();
            if(level<3) for(var entry:BuildingDevelopment.upgradeCost(level).entrySet()) {var cost=new CompoundTag();cost.putString("resource",entry.getKey().key());cost.putInt("amount",entry.getValue());cost.putInt("available",account.balance(entry.getKey()));costs.add(cost);}
            row.put("costs",costs);row.putString("building_kind",building.kind().name().toLowerCase(Locale.ROOT));
            if(building.kind()==dev.livingkingdoms.structure.BuildingKind.TAVERN) row.putInt("hire_price",dev.livingkingdoms.economy.TavernService.price(level));
            rows.add(row);ids.add(building.id());
        }
        for(var profile:jobs.professions(session.kingdom)) if(profile.active() && profile.type()==ProfessionType.GUARD) {
            var citizen=CitizenSavedData.get(player.server).citizen(profile.citizenId()).orElse(null);if(citizen==null) continue;
            var row=new CompoundTag();row.putUUID("id",citizen.id());row.putString("kind","guard");row.putString("name",citizen.name());row.putInt("level",profile.level().value());
            row.putBoolean("equip",account.balance(StockResource.EQUIPMENT)>0 && player.serverLevel().getEntity(citizen.entityId())!=null);
            row.putBoolean("archer",player.serverLevel().getEntity(citizen.entityId()) instanceof net.minecraft.world.entity.npc.Villager guard && dev.livingkingdoms.profession.GuardEquipment.archer(guard));
            row.putBoolean("loaded",player.serverLevel().getEntity(citizen.entityId())!=null);row.putInt("equipment",account.balance(StockResource.EQUIPMENT));rows.add(row);ids.add(citizen.id());
        }
        SESSIONS.put(player,new Session(session.token,session.kingdom,session.dimension,session.anchor,session.opened,Set.copyOf(ids),session.focus));
        var tag=new CompoundTag();tag.putUUID("session",session.token);if(session.focus!=null) tag.putUUID("focus",session.focus);tag.putUUID("kingdom",session.kingdom);tag.putString("kingdom_name",realm.name());tag.putLong("revision",account.revision());tag.put("entries",rows);
        tag.put("history",dev.livingkingdoms.chronicle.ChronicleService.history(player.server,session.kingdom));
        tag.putBoolean("expand",BuildingDevelopment.canExpand(player.server,realm));
        var wall=new CompoundTag();int wallTier=dev.livingkingdoms.rpg.WallService.tier(player.server,realm.id());wall.putInt("tier",wallTier);
        var blocked=dev.livingkingdoms.rpg.WallService.blocked(player.server,realm);blocked.ifPresent(reason->wall.putString("blocked",reason));
        if(wallTier<dev.livingkingdoms.rpg.domain.Fortification.MAX_TIER) {var wallCosts=new ListTag();
            for(var entry:dev.livingkingdoms.rpg.WallService.nextCost(player.server,realm).entrySet()) {var cost=new CompoundTag();cost.putString("resource",entry.getKey().key());cost.putInt("amount",entry.getValue());cost.putInt("available",account.balance(entry.getKey()));wallCosts.add(cost);}
            wall.put("costs",wallCosts);}
        wall.putInt("breaches",dev.livingkingdoms.rpg.WallService.breaches(realm.id()));tag.put("wall",wall);if(notice!=null) tag.putString("notice",notice);return tag;
    }
    /** Switches a guard between sword (patrol) and bow (archer on towers and wall posts). */
    private static boolean toggleArcher(ServerPlayer player,UUID kingdom,UUID citizenId) {
        var citizen=dev.livingkingdoms.citizen.persistence.CitizenSavedData.get(player.server).citizen(citizenId).filter(c->c.settlementId().equals(kingdom)).orElse(null);
        if(citizen==null || !(player.serverLevel().getEntity(citizen.entityId()) instanceof net.minecraft.world.entity.npc.Villager guard)) return false;
        var job=ProfessionSavedData.get(player.server).profession(citizenId).filter(p->p.active() && p.type()==ProfessionType.GUARD).orElse(null);
        if(job==null) return false;
        dev.livingkingdoms.profession.GuardEquipment.setArcher(guard,job,!dev.livingkingdoms.profession.GuardEquipment.archer(guard),player);
        return true;
    }
}

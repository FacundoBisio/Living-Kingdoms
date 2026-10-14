package dev.livingkingdoms.economy;

import dev.livingkingdoms.citizen.domain.Housing;
import dev.livingkingdoms.config.EconomyConfig;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.defense.DefenseService;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.domain.*;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Levels change functional capacity/work output; expansion changes the actual indexed territory. No terrain overwrites. */
public final class BuildingDevelopment {
    private BuildingDevelopment() {}
    public static int level(MinecraftServer server,UUID settlement,UUID building) {
        return EconomySavedData.get(server).account(settlement).map(account->account.buildingLevel(building)).orElse(1);
    }
    public static int warehouseLevels(MinecraftServer server,UUID settlement) {
        return SettlementSavedData.get(server).layout(settlement).map(layout->layout.buildings().stream().filter(b->b.kind()==BuildingKind.WAREHOUSE)
                .mapToInt(b->level(server,settlement,Housing.identity(settlement,b))).sum()).orElse(0);
    }
    public static Map<StockResource,Integer> upgradeCost(int current) {
        if(current<1 || current>5) throw new IllegalArgumentException("Invalid upgrade level");
        return Map.of(StockResource.WOOD,32*current,StockResource.STONE,16*current,StockResource.IRON,4*current);
    }
    public static boolean upgrade(ServerPlayer player,UUID settlement,UUID building) {
        if(!KingdomEconomy.authorized(player,settlement)) return false;
        var s=SettlementSavedData.get(player.server).get(settlement).orElseThrow();ProfessionService.ensure(player.serverLevel(),s);
        var target=ProfessionSavedData.get(player.server).building(building).filter(b->b.active() && b.settlementId().equals(settlement)).orElse(null);
        int current=level(player.server,settlement,building);
        if(target==null || !upgradeable(target.kind()) || current>=Math.min(3,EconomyConfig.MAX_BUILDING_LEVEL.get())) return false;
        var payment=KingdomEconomy.plan(player.server,settlement,upgradeCost(current),Map.of(),false);if(payment.isEmpty()) return false;
        var transfer=payment.orElseThrow();var developed=transfer.after().upgrade(building);
        if(!KingdomEconomy.commit(player.server,transfer)) return false;
        if(!EconomySavedData.get(player.server).replace(settlement,transfer.after(),developed)) throw new IllegalStateException("Upgrade changed during commit");
        if(target.kind()==BuildingKind.CORE || target.kind()==BuildingKind.TOWN_HALL) {
            var next=new Settlement(s.id(),s.name(),s.faction(),Math.max(s.level(),current+1),s.population(),s.territory(),s.provenance(),s.lifecycle());
            if(!SettlementSavedData.get(player.server).replace(s,next)) throw new IllegalStateException("Town Hall changed during upgrade");
        }
        ProfessionService.ensure(player.serverLevel(),SettlementSavedData.get(player.server).get(settlement).orElseThrow());KingdomEconomy.ensure(player.server,settlement);
        dev.livingkingdoms.chronicle.ChronicleService.buildingUpgraded(player.server,settlement,building,current+1);
        dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player,"first_upgrade");
        if(SettlementSavedData.get(player.server).get(settlement).orElseThrow().level()>=3) dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player,"development3");
        return true;
    }
    public static boolean upgradeable(BuildingKind kind) {
        return switch(kind) {
            case CORE,TOWN_HALL,FARM,BARRACKS,WATCHTOWER,WAREHOUSE,LUMBERJACK_HUT,MINE,BLACKSMITH,MARKET,HOUSE,HOUSE_VARIANT,HOUSE_THIRD -> true;
            default -> false;
        };
    }
    public static boolean repair(ServerPlayer player,UUID settlement,UUID building) {
        if(!KingdomEconomy.authorized(player,settlement)) return false;
        var s=SettlementSavedData.get(player.server).get(settlement).orElseThrow();ProfessionService.ensure(player.serverLevel(),s);
        var target=ProfessionSavedData.get(player.server).building(building).filter(b->b.active() && b.settlementId().equals(settlement)
                && !b.template().getPath().equals("allied/converted/plaza")).orElse(null);
        if(target==null) return false;
        var account=KingdomEconomy.ensure(player.server,settlement);
        return !account.repairOrders().contains(building) && EconomySavedData.get(player.server).replace(settlement,account,account.repair(building,true));
    }
    public static boolean canExpand(MinecraftServer server,Settlement settlement) {
        if(settlement.lifecycle()!=SettlementLifecycle.ESTABLISHED || settlement.territory().radius()+16>EconomyConfig.MAX_TERRITORY_RADIUS.get()
                || ConstructionSavedData.get(server).projects(settlement.id()).stream().anyMatch(e->e.project().state()!=ConstructionState.COMPLETED)
                || DefenseService.status(server,settlement.id())==dev.livingkingdoms.defense.domain.SettlementSafety.THREATENED) return false;
        return ProfessionSavedData.get(server).buildings(settlement.id()).stream().anyMatch(b->b.active()
                && (b.kind()==BuildingKind.CORE || b.kind()==BuildingKind.TOWN_HALL) && level(server,settlement.id(),b.id())>=2)
                && SettlementSavedData.get(server).canExpand(settlement,settlement.territory().radius()+16);
    }
    public static boolean expand(ServerPlayer player,UUID settlement) {
        if(!KingdomEconomy.authorized(player,settlement)) return false;
        var s=SettlementSavedData.get(player.server).get(settlement).orElseThrow();ProfessionService.ensure(player.serverLevel(),s);
        if(!canExpand(player.server,s)) return false;
        int radius=s.territory().radius()+16;var t=s.territory();var border=player.serverLevel().getWorldBorder();
        for(int[] sign:new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}}) if(!border.isWithinBounds(new BlockPos(t.x()+sign[0]*radius,t.y(),t.z()+sign[1]*radius))) return false;
        var payment=KingdomEconomy.plan(player.server,settlement,Map.of(StockResource.WOOD,64,StockResource.STONE,32,StockResource.IRON,8),Map.of(),false);
        if(payment.isEmpty()) return false;
        var transfer=payment.orElseThrow();
        if(!KingdomEconomy.valid(player.server,transfer) || !SettlementSavedData.get(player.server).expand(s,radius)) return false;
        if(!KingdomEconomy.commit(player.server,transfer)) throw new IllegalStateException("Expansion changed during commit");return true;
    }
}

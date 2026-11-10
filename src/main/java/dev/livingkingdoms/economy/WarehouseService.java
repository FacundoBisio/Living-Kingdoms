package dev.livingkingdoms.economy;

import dev.livingkingdoms.config.ProfessionConfig;
import dev.livingkingdoms.profession.ProfessionService;
import dev.livingkingdoms.profession.domain.FunctionalBuilding;
import dev.livingkingdoms.profession.persistence.ProfessionSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import dev.livingkingdoms.structure.BuildingKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/**
 * Warehouses are real stores: every 30 seconds, logs, stone, iron ingots and wheat left in the chests and barrels
 * inside a loaded Warehouse move into Kingdom Storage (up to its free room). Only kingdoms with a player inside are
 * visited, and only the containers within each warehouse footprint are read.
 */
public final class WarehouseService {
    public static final int INTERVAL = 600;
    private WarehouseService() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.overworld().getGameTime() % INTERVAL != 37) return;
        var seen = new HashSet<UUID>();
        for (var player : server.getPlayerList().getPlayers()) {
            var settlement = SettlementSavedData.get(server).alliedAt(player.level().dimension().location().toString(), player.getBlockX(), player.getBlockZ()).orElse(null);
            if (settlement == null || !seen.add(settlement.id())) continue;
            for (var b : ProfessionSavedData.get(server).buildings(settlement.id()))
                if (b.active() && b.kind() == BuildingKind.WAREHOUSE) sweep(player.serverLevel(), b);
        }
    }

    static StockResource resource(ItemStack stack) {
        if (stack.is(ItemTags.LOGS)) return StockResource.WOOD;
        if (stack.is(Items.STONE)) return StockResource.STONE;
        if (stack.is(Items.IRON_INGOT)) return StockResource.IRON;
        if (stack.is(Items.WHEAT)) return StockResource.FOOD;
        return null;
    }

    /** Moves what fits into Kingdom Storage; returns the number of items moved. */
    public static int sweep(ServerLevel level, FunctionalBuilding b) {
        var server = level.getServer(); var id = b.settlementId();
        var box = b.bounds(); int y0 = b.origin().getY();
        if (!level.hasChunkAt(new BlockPos(box.minX(), y0, box.minZ())) || !level.hasChunkAt(new BlockPos(box.maxX(), y0, box.maxZ()))) return 0;
        var account = KingdomEconomy.ensure(server, id); var food = ProfessionService.food(server, id);
        int wheat = ProfessionConfig.WHEAT_FOOD.get();
        var room = new EnumMap<StockResource, Integer>(StockResource.class);
        for (var r : StockResource.values()) if (r.material() != null)
            room.put(r, r == StockResource.FOOD ? Math.max(0, food.capacity() - food.stock()) / wheat : account.room(r));
        var gained = new EnumMap<StockResource, Integer>(StockResource.class);
        record Take(Container container, int slot, int count) {}
        var takes = new ArrayList<Take>();
        for (int y = y0; y <= y0 + 6; y++) for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) {
            if (!(level.getBlockEntity(new BlockPos(x, y, z)) instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot); var r = stack.isEmpty() ? null : resource(stack);
                if (r == null) continue;
                int free = room.getOrDefault(r, 0) - gained.getOrDefault(r, 0);
                int take = Math.min(free, stack.getCount());
                if (take <= 0) continue;
                gained.merge(r, take, Integer::sum); takes.add(new Take(container, slot, take));
            }
        }
        if (takes.isEmpty()) return 0;
        var units = new EnumMap<StockResource, Integer>(StockResource.class);
        gained.forEach((r, items) -> units.put(r, r.unitsForItems(items, wheat)));
        var transfer = KingdomEconomy.plan(server, id, Map.of(), units, false).orElse(null);
        if (transfer == null || !KingdomEconomy.valid(server, transfer) || !KingdomEconomy.commit(server, transfer)) return 0;
        int moved = 0;
        for (var take : takes) { take.container().removeItem(take.slot(), take.count()); take.container().setChanged(); moved += take.count(); }
        return moved;
    }

    /** Deposits every kingdom resource the player carries (up to storage room). Returns items moved. */
    public static int depositAll(net.minecraft.server.level.ServerPlayer player, UUID settlement) {
        int moved = 0;
        for (var r : StockResource.values()) {
            if (r.material() == null) continue;
            for (int guard = 0; guard < 40; guard++) { int n = KingdomEconomy.deposit(player, settlement, r, 64); if (n <= 0) break; moved += n; }
        }
        return moved;
    }
}

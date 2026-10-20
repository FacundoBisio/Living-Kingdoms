package dev.livingkingdoms.construction;

import dev.livingkingdoms.economy.EconomySavedData;
import dev.livingkingdoms.economy.StockResource;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionStockSupplyTest {
    @Test void keepBackLimitsAutomaticSupplyWithoutExceedingMissing() {
        assertEquals(10, ConstructionService.supplyAmount(10, 64, 8));
        assertEquals(56, ConstructionService.supplyAmount(60, 64, 8));
        assertEquals(0, ConstructionService.supplyAmount(10, 8, 8));
        assertEquals(0, ConstructionService.supplyAmount(10, 3, 8));
        assertEquals(0, ConstructionService.supplyAmount(0, 64, 0));
        assertEquals(64, ConstructionService.supplyAmount(100, 64, 0));
    }
    @Test void staleFundingReceiptCannotSpendSharedStockTwice() {
        var data = new EconomySavedData(); UUID settlement = UUID.randomUUID();
        var empty = data.ensure(settlement, 256, 0);
        var stocked = empty.exchange(Map.of(), Map.of(StockResource.WOOD, 64), false).orElseThrow();
        assertTrue(data.replace(settlement, empty, stocked));
        int amount = ConstructionService.supplyAmount(60, stocked.balance(StockResource.WOOD), 8);
        var funded = stocked.exchange(Map.of(StockResource.WOOD, amount), Map.of(), false).orElseThrow();
        var competing = stocked.exchange(Map.of(StockResource.WOOD, amount), Map.of(), false).orElseThrow();
        assertTrue(data.replace(settlement, stocked, funded));
        assertFalse(data.replace(settlement, stocked, competing));
        assertEquals(8, data.account(settlement).orElseThrow().balance(StockResource.WOOD));
    }
}

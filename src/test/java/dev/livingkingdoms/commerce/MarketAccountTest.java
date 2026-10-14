package dev.livingkingdoms.commerce;

import dev.livingkingdoms.economy.StockResource;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketAccountTest {
    @Test void finiteTreasuryAndPerDirectionQuotasNeverCreateMoneyAtRestock() {
        var initial=MarketAccount.empty(3,0,24000);
        var sold=initial.trade(StockResource.IRON,false,3,2).orElseThrow();assertEquals(0,sold.emeralds());
        assertTrue(sold.trade(StockResource.WOOD,false,1,2).isEmpty());
        var bought=sold.trade(StockResource.IRON,true,4,2).orElseThrow().trade(StockResource.IRON,true,4,2).orElseThrow();
        assertTrue(bought.trade(StockResource.IRON,true,4,2).isEmpty());
        assertSame(bought,bought.restock(23999,24000));
        var replenished=bought.restock(24000,24000);assertEquals(8,replenished.emeralds());assertEquals(2,replenished.remaining(StockResource.IRON,true,2));
        assertEquals(48000,replenished.restockAt());assertSame(replenished,replenished.restock(24000,24000));
    }
    @Test void malformedMarketDataIsRejectedAndEmptyCountersCanonicalize() {
        assertEquals(MarketAccount.empty(3,0,20),new MarketAccount(3,0,20,Map.of(StockResource.WOOD,0),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new MarketAccount(-1,0,0,Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new MarketAccount(0,0,0,Map.of(StockResource.WOOD,129),Map.of()));
        var bad=new CompoundTag();bad.putInt("schema",99);bad.put("accounts",new ListTag());
        assertThrows(IllegalArgumentException.class,()->CommerceSavedData.load(bad,null));
        bad.putInt("schema",1);var rows=new ListTag();rows.add(new CompoundTag());bad.put("accounts",rows);
        assertThrows(IllegalArgumentException.class,()->CommerceSavedData.load(bad,null));
    }
    @Test void ledgerSaturatesWithoutOverflowAndRejectsInvalidTradeSizes() {
        var full=new MarketAccount(1_000_000,0,100,Map.of(),Map.of());assertTrue(full.trade(StockResource.WOOD,true,64,16).isEmpty());
        assertTrue(full.trade(StockResource.WOOD,false,0,16).isEmpty());
        assertTrue(full.trade(StockResource.WOOD,false,1,129).isEmpty());
        assertTrue(full.trade(null,true,2,16).isEmpty());
        assertThrows(NullPointerException.class,()->new CommerceSavedData().ensure(null,0));
    }
}

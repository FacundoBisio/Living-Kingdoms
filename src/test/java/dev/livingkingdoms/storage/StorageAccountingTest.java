package dev.livingkingdoms.storage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StorageAccountingTest {
    @Test void nativeSignedShortTransportPreservesCapacitiesAbove32767() {
        for(int value:new int[]{0,1,32767,32768,65535,65536,1_000_000,Integer.MAX_VALUE})
            assertEquals(value,StorageAccounting.join((short)StorageAccounting.low(value),(short)StorageAccounting.high(value)));
    }
}

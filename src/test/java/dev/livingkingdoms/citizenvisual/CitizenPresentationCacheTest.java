package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CitizenPresentationCacheTest {
    private static CitizenPresentation data(int id) {
        return new CitizenPresentation(id, new UUID(0, id), "Ada", 1, ProfessionType.UNASSIGNED,
                1, WorkState.IDLE, false, false, false, "", 0, "");
    }
    @Test void entityIdReuseExpiryUnloadAndClockResetNeverLeakVisualIdentity() {
        var cache = new CitizenPresentationCache(2, 400); var original = data(3);
        cache.put(original, 10, false);
        assertNull(cache.get(3, UUID.randomUUID(), 11));
        assertNull(cache.get(4, original.entityUuid(), 11));
        cache.put(original, 10, false); assertNull(cache.get(3, original.entityUuid(), 411));
        cache.put(original, 10, false); assertNull(cache.get(3, original.entityUuid(), 9));
        cache.put(original, 10, false); cache.remove(original.entityUuid()); assertNull(cache.get(3, original.entityUuid(), 10));
        cache.put(original, 10, false); cache.clear(); assertEquals(0, cache.size());
    }
    @Test void pressureEvictsOldestAndHeartbeatsNeverReplayWorkPulses() {
        var cache = new CitizenPresentationCache(2, 400);
        cache.put(data(1), 5, true); cache.put(data(1), 20, false);
        assertEquals(5, cache.get(1, data(1).entityUuid(), 20).actionAt());
        cache.put(data(2), 20, false); cache.put(data(3), 20, false);
        assertEquals(2, cache.size()); assertNull(cache.get(1, data(1).entityUuid(), 20));
        assertNotNull(cache.get(3, data(3).entityUuid(), 20));
    }
    @Test void clientCachesCannotExceedThePresentationBudget() {
        assertThrows(IllegalArgumentException.class, () -> new CitizenPresentationCache(1025, 400));
        var cache = new CitizenPresentationCache(CitizenPresentationCache.MAX_CAPACITY, 400);
        for (int id = 1; id <= 2048; id++) cache.put(data(id), 0, false);
        assertEquals(1024, cache.size());
        assertNull(cache.get(1024, data(1024).entityUuid(), 1));
        assertNotNull(cache.get(1025, data(1025).entityUuid(), 1));
    }
}

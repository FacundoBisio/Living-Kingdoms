package dev.livingkingdoms.chronicle;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleSavedDataTest {
    @TempDir Path directory;
    private static ChronicleEvent event(UUID kingdom, int index) {
        return new ChronicleEvent(UUID.randomUUID(), kingdom, ChronicleEvent.Kind.CONSTRUCTION_COMPLETED, index, "order-" + index, "house");
    }
    @Test void historyAndReceiptsRemainBoundedAndRecentDuplicateSurvivesReload() {
        var data = new ChronicleSavedData(); UUID kingdom = UUID.randomUUID();
        for (int i = 0; i < 600; i++) assertTrue(data.record(event(kingdom, i)));
        assertEquals(128, data.history(kingdom).size()); assertEquals(472, data.history(kingdom).getFirst().tick());
        var tag = data.save(new CompoundTag(), null);
        assertEquals(512, tag.getList("kingdoms", Tag.TAG_COMPOUND).getCompound(0).getList("receipts", Tag.TAG_STRING).size());
        var reopened = ChronicleSavedData.load(tag, null);
        assertFalse(reopened.isDirty()); assertFalse(reopened.record(event(kingdom, 599))); assertFalse(reopened.record(event(kingdom, 200)));
        assertEquals(data.history(kingdom), reopened.history(kingdom));
        assertThrows(UnsupportedOperationException.class, () -> reopened.history(kingdom).clear());
    }
    @Test void receiptsAreScopedToKingdomAndEventKind() {
        var data = new ChronicleSavedData(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(data.record(event(a, 1))); assertTrue(data.record(event(b, 1))); assertFalse(data.record(event(a, 1)));
        assertTrue(data.record(new ChronicleEvent(UUID.randomUUID(), a, ChronicleEvent.Kind.UPGRADE_COMPLETED, 1, "order-1", "2")));
        assertEquals(2, data.history(a).size()); assertEquals(1, data.history(b).size());
    }
    @Test void deathReceiptCannotReplayWithChangedGuardClassificationAndLoadEmitsNothing() {
        var data = new ChronicleSavedData(); UUID kingdom = UUID.randomUUID(); var delivered = new ArrayList<ChronicleEvent>();
        data.observeTransitions(delivered::add);
        assertTrue(data.record(new ChronicleEvent(UUID.randomUUID(), kingdom, ChronicleEvent.Kind.GUARD_DIED, 1, "citizen-1", "Ada")));
        assertFalse(data.record(new ChronicleEvent(UUID.randomUUID(), kingdom, ChronicleEvent.Kind.CITIZEN_DIED, 2, "citizen-1", "Ada")));
        assertEquals(1, delivered.size());
        var loaded = ChronicleSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(1, delivered.size()); assertEquals(ChronicleEvent.Kind.GUARD_DIED, loaded.history(kingdom).getFirst().kind());
    }
    @Test void kingdomCapacityRefusesAdditionalLedgerWhileExistingKingdomStillRecords() {
        var data = new ChronicleSavedData(); UUID first = UUID.randomUUID(); assertTrue(data.record(event(first, 1)));
        for (int i = 1; i < ChronicleSavedData.KINGDOM_LIMIT; i++) assertTrue(data.record(event(UUID.randomUUID(), 1)));
        UUID overflow = UUID.randomUUID(); assertFalse(data.record(event(overflow, 1))); assertTrue(data.history(overflow).isEmpty());
        assertTrue(data.record(event(first, 2))); assertEquals(2, data.history(first).size());
    }
    @Test void rejectsUnknownSchemaMalformedUuidAndMissingReceipts() {
        var data = new ChronicleSavedData(); data.record(event(UUID.randomUUID(), 1)); var tag = data.save(new CompoundTag(), null);
        var future = tag.copy(); future.putInt("schema_version", 2);
        assertThrows(IllegalArgumentException.class, () -> ChronicleSavedData.load(future, null));
        var invalid = tag.copy(); invalid.getList("kingdoms", Tag.TAG_COMPOUND).getCompound(0).remove("settlement");
        assertThrows(IllegalArgumentException.class, () -> ChronicleSavedData.load(invalid, null));
        tag.getList("kingdoms", Tag.TAG_COMPOUND).getCompound(0).put("receipts", new ListTag());
        assertThrows(IllegalArgumentException.class, () -> ChronicleSavedData.load(tag, null));
    }
    @Test void legacyWorldCreatesNewStoreWithoutTouchingOldStoreAndCorruptionFailsClosed() throws Exception {
        SharedConstants.tryDetectVersion(); Path legacy = directory.resolve("livingkingdoms_settlements.dat");
        byte[] original = new byte[] {1, 2, 3}; Files.write(legacy, original);
        var storage = new DimensionDataStorage(directory.toFile(), null, null);
        var data = ChronicleSavedData.getOrCreate(storage, directory); data.record(event(UUID.randomUUID(), 1));
        storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(original, Files.readAllBytes(legacy));
        var reopened = new DimensionDataStorage(directory.toFile(), null, null);
        assertFalse(ChronicleSavedData.getOrCreate(reopened, directory).isDirty());
        Path file = directory.resolve(ChronicleSavedData.DATA_NAME + ".dat"); byte[] corrupt = new byte[] {0x1f, (byte) 0x8b, 0x08}; Files.write(file, corrupt);
        var broken = new DimensionDataStorage(directory.toFile(), null, null);
        assertThrows(IllegalStateException.class, () -> ChronicleSavedData.getOrCreate(broken, directory));
        assertThrows(IllegalStateException.class, () -> ChronicleSavedData.getOrCreate(broken, directory));
        broken.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(corrupt, Files.readAllBytes(file));
    }
    @Test void toastQueueDropsOldestBoundsBurstAndDeduplicates() {
        var queue = new ChronicleNoticeQueue(); UUID kingdom = UUID.randomUUID();
        var first = new ChroniclePayloads.Notice(event(kingdom, 1), "Realm");
        assertTrue(queue.offer(first)); assertFalse(queue.offer(first));
        for (int i = 2; i <= 12; i++) queue.offer(new ChroniclePayloads.Notice(event(kingdom, i), "Realm"));
        assertEquals(8, queue.size()); assertEquals(5, queue.poll().orElseThrow().event().tick());
        queue.clear(); assertEquals(0, queue.size()); assertTrue(queue.offer(first));
    }
}

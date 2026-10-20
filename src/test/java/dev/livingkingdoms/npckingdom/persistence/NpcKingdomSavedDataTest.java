package dev.livingkingdoms.npckingdom.persistence;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcKingdomSavedDataTest {
    @TempDir Path directory;

    private DimensionDataStorage storage() {
        SharedConstants.tryDetectVersion();
        return new DimensionDataStorage(directory.toFile(), null, null);
    }

    @Test void saveAndReopenPreservesRegistryCellsRetriesAndCooldown() {
        var storage = storage();
        var data = NpcKingdomSavedData.getOrCreate(storage, directory);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(data.register(first, "Aldric Ashford", 100));
        assertTrue(data.register(second, "Maren Greymoor", 200));
        assertFalse(data.register(first, "Other Name", 300), "A ruler is never replaced");
        data.markAttempted(7L);
        data.markAttempted(-3L);
        assertTrue(data.retryUnloaded(11L));
        data.setNextAttemptAt(6400);
        assertTrue(data.isDirty());
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var reopened = NpcKingdomSavedData.getOrCreate(storage(), directory);
        assertEquals(List.of(first, second), reopened.ids());
        assertEquals("Aldric Ashford", reopened.ruler(first).orElseThrow());
        assertEquals(200, reopened.kingdom(second).orElseThrow().createdAt());
        assertTrue(reopened.isNpc(second) && !reopened.isNpc(UUID.randomUUID()));
        assertTrue(reopened.attempted(7L) && reopened.attempted(-3L) && !reopened.attempted(11L));
        assertEquals(1, reopened.retries(11L));
        assertEquals(6400, reopened.nextAttemptAt());
        assertFalse(reopened.isDirty());
    }

    @Test void unloadedRetriesAreBoundedThenRecordTheCell() {
        var data = new NpcKingdomSavedData();
        assertTrue(data.retryUnloaded(5L));
        assertTrue(data.retryUnloaded(5L));
        assertFalse(data.retryUnloaded(5L), "Third unloaded outcome abandons the cell");
        assertTrue(data.attempted(5L));
        assertEquals(0, data.retries(5L));
        assertFalse(data.retryUnloaded(5L));
    }

    @Test void attemptedCellsEvictEldestAtCap() {
        var data = new NpcKingdomSavedData();
        for (long cell = 0; cell < NpcKingdomSavedData.MAX_ATTEMPTED_CELLS + 5; cell++) data.markAttempted(cell);
        assertEquals(NpcKingdomSavedData.MAX_ATTEMPTED_CELLS, data.attemptedCount());
        for (long cell = 0; cell < 5; cell++) assertFalse(data.attempted(cell));
        assertTrue(data.attempted(5L) && data.attempted(NpcKingdomSavedData.MAX_ATTEMPTED_CELLS + 4L));
        var reloaded = NpcKingdomSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(NpcKingdomSavedData.MAX_ATTEMPTED_CELLS, reloaded.attemptedCount());
    }

    @Test void malformedRecordsAreRejected() {
        var valid = new NpcKingdomSavedData();
        valid.register(UUID.randomUUID(), "Edwin Redmere", 1);
        CompoundTag tag = valid.save(new CompoundTag(), null);
        var longRuler = tag.copy();
        ((CompoundTag) ((ListTag) longRuler.get("kingdoms")).get(0)).putString("ruler", "x".repeat(33));
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomSavedData.load(longRuler, null));
        var missing = tag.copy();
        missing.remove("next_attempt_at");
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomSavedData.load(missing, null));
        var duplicate = tag.copy();
        ((ListTag) duplicate.get("kingdoms")).add(((ListTag) tag.get("kingdoms")).get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomSavedData.load(duplicate, null));
        var retry = tag.copy();
        var entry = new CompoundTag();
        entry.putLong("cell", 1L);
        entry.putInt("count", 3);
        ((ListTag) retry.get("retries")).add(entry);
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomSavedData.load(retry, null));
        var negative = tag.copy();
        negative.putLong("next_attempt_at", -1);
        assertThrows(IllegalArgumentException.class, () -> NpcKingdomSavedData.load(negative, null));
    }

    @Test void corruptFileCannotBeSilentlyReplaced() throws Exception {
        Path file = directory.resolve(NpcKingdomSavedData.DATA_NAME + ".dat");
        byte[] damaged = {0x1f, (byte) 0x8b, 0x08};
        Files.write(file, damaged);
        var storage = storage();
        assertThrows(IllegalStateException.class, () -> NpcKingdomSavedData.getOrCreate(storage, directory));
        assertThrows(IllegalStateException.class, () -> NpcKingdomSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(damaged, Files.readAllBytes(file));
    }

    @Test void futureSchemaCannotBeOverwritten() throws Exception {
        var tag = new NpcKingdomSavedData().save(new CompoundTag(), null);
        tag.putInt("schema_version", NpcKingdomSavedData.SCHEMA_VERSION + 1);
        var root = new CompoundTag();
        root.put("data", tag);
        Path file = directory.resolve(NpcKingdomSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        byte[] original = Files.readAllBytes(file);
        var storage = storage();
        assertThrows(IllegalStateException.class, () -> NpcKingdomSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}

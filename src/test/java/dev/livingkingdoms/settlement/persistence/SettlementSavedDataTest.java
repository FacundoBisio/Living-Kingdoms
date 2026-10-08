package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementSavedDataTest {
    @TempDir Path directory;

    private static Settlement settlement(String dimension, int x) {
        return new Settlement(UUID.randomUUID(), "Test Haven", Faction.ALLIED, 2, 17,
                new Territory(dimension, x, 72, -32, 48));
    }

    private DimensionDataStorage newStorage() {
        SharedConstants.tryDetectVersion();
        // The custom Factory disables DFU and the serialized fields use no registries.
        return new DimensionDataStorage(directory.toFile(), null, null);
    }

    @Test
    void dimensionDataStorageSavesAndReopensSettlementData() {
        DimensionDataStorage storage = newStorage();
        SettlementSavedData data = SettlementSavedData.getOrCreate(storage, directory);
        assertSame(data, SettlementSavedData.getOrCreate(storage, directory));
        Settlement settlement = settlement("minecraft:overworld", 120);
        data.add(settlement);
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(Files.isRegularFile(directory.resolve(SettlementSavedData.DATA_NAME + ".dat")));

        SettlementSavedData reopened = SettlementSavedData.getOrCreate(newStorage(), directory);
        IOUtilities.waitUntilIOWorkerComplete();
        assertNotSame(data, reopened);
        assertEquals(List.of(settlement), reopened.settlements());
        assertFalse(reopened.isDirty());
    }

    @Test
    void founderOriginAndCreationTimeSurviveActualSavedDataFileReopen() {
        DimensionDataStorage storage = newStorage();
        var data = SettlementSavedData.getOrCreate(storage,directory);
        UUID founder = UUID.randomUUID();
        var settlement = Settlement.established(UUID.randomUUID(),new Territory("minecraft:overworld",120,72,-32,48),2,
                dev.livingkingdoms.settlement.domain.SettlementOrigin.CONVERTED,founder);
        data.add(settlement); storage.save(); IOUtilities.waitUntilIOWorkerComplete();
        var reopened = SettlementSavedData.getOrCreate(newStorage(),directory);
        IOUtilities.waitUntilIOWorkerComplete();
        assertEquals(settlement,reopened.get(settlement.id()).orElseThrow());
        assertEquals(founder,reopened.get(settlement.id()).orElseThrow().provenance().founder().orElseThrow());
        assertFalse(reopened.isDirty());
    }

    @Test
    void dimensionDataStorageRefusesFutureSchemaWithoutOverwritingFile() throws IOException {
        CompoundTag future = new SettlementSavedData().save(new CompoundTag(), null);
        future.putInt("schema_version", 99);
        CompoundTag root = new CompoundTag();
        root.put("data", future);
        Path file = directory.resolve(SettlementSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        assertUnloadedFileIsPreserved(file);
    }

    @Test
    void dimensionDataStorageRefusesTruncatedFileWithoutOverwritingIt() throws IOException {
        Path file = directory.resolve(SettlementSavedData.DATA_NAME + ".dat");
        Files.write(file, new byte[]{0x1f, (byte) 0x8b, 0x08});
        assertUnloadedFileIsPreserved(file);
    }

    private void assertUnloadedFileIsPreserved(Path file) throws IOException {
        byte[] original = Files.readAllBytes(file);
        DimensionDataStorage storage = newStorage();
        assertThrows(IllegalStateException.class, () -> SettlementSavedData.getOrCreate(storage, directory));
        // The failed load is cached as null by vanilla; retries must remain closed too.
        assertThrows(IllegalStateException.class, () -> SettlementSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void diskRoundTripPreservesEveryFieldAcrossDimensions() throws IOException {
        SettlementSavedData data = new SettlementSavedData();
        Settlement overworld = settlement("minecraft:overworld", -120);
        Settlement nether = new Settlement(UUID.randomUUID(), "Pillager Test Camp", Faction.PILLAGER, 3, 23,
                new Territory("minecraft:the_nether", -120, 58, -32, 24));
        data.add(overworld);
        data.add(nether);
        CompoundTag root = new CompoundTag();
        // SavedData wraps custom fields in a vanilla 'data' tag.
        root.put("data", data.save(new CompoundTag(), null));
        Path file = directory.resolve(SettlementSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        CompoundTag disk = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        SettlementSavedData reopened = SettlementSavedData.load(disk.getCompound("data"), null);
        assertEquals(List.of(overworld, nether), reopened.settlements());
        assertFalse(reopened.isDirty());
        assertEquals(overworld, reopened.at("minecraft:overworld", -120, -32).orElseThrow());
        assertEquals(nether, reopened.at("minecraft:the_nether", -120, -32).orElseThrow());
    }

    @Test
    void addMarksDirtyAndRejectsDuplicateIdentityAndOverlappingTerritory() {
        SettlementSavedData data = new SettlementSavedData();
        assertFalse(data.isDirty());
        Settlement settlement = settlement("minecraft:overworld", 0);
        data.add(settlement);
        assertTrue(data.isDirty());
        assertThrows(IllegalArgumentException.class, () -> data.add(settlement));
        assertThrows(IllegalArgumentException.class, () -> data.add(settlement("minecraft:overworld", 10)));
        assertEquals(1, data.settlements().size());
        assertThrows(UnsupportedOperationException.class, () -> data.settlements().clear());
    }

    @Test
    void emptyStoreReloadsAndQueriesOutsideTerritoryAreEmpty() {
        SettlementSavedData data = new SettlementSavedData();
        SettlementSavedData reopened = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(reopened.settlements().isEmpty());
        reopened.add(settlement("minecraft:overworld", 0));
        assertTrue(reopened.at("minecraft:overworld", 1000, 1000).isEmpty());
        assertTrue(reopened.at("minecraft:the_end", 0, -32).isEmpty());
    }

    @Test
    void rejectsUnsupportedSchemaAndMalformedEntries() {
        CompoundTag future = new SettlementSavedData().save(new CompoundTag(), null);
        future.putInt("schema_version", 99);
        assertThrows(IllegalArgumentException.class, () -> SettlementSavedData.load(future, null));
        assertThrows(IllegalArgumentException.class, () -> SettlementSavedData.load(new CompoundTag(), null));
        CompoundTag corrupt = new SettlementSavedData().save(new CompoundTag(), null);
        ListTag entries = new ListTag();
        entries.add(new CompoundTag());
        corrupt.put("settlements", entries);
        assertThrows(IllegalArgumentException.class, () -> SettlementSavedData.load(corrupt, null));
    }
}

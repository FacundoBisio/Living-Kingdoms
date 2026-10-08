package dev.livingkingdoms.construction;

import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionStorageGuardTest {
    @TempDir Path directory;
    private DimensionDataStorage storage() { SharedConstants.tryDetectVersion(); return new DimensionDataStorage(directory.toFile(),null,null); }
    @Test void corruptConstructionCannotBeSilentlyReplaced() throws Exception {
        Path file=directory.resolve(ConstructionSavedData.DATA_NAME+".dat"); byte[] damaged={0x1f,(byte)0x8b,0x08}; Files.write(file,damaged);
        var storage=storage(); assertThrows(IllegalStateException.class,()->ConstructionSavedData.getOrCreate(storage,directory));
        assertThrows(IllegalStateException.class,()->ConstructionSavedData.getOrCreate(storage,directory));
        storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(damaged,Files.readAllBytes(file));
    }
    @Test void unsupportedSchemaCannotBeOverwrittenAfterVanillaCatchesLoadFailure() throws Exception {
        var tag=new ConstructionSavedData().save(new CompoundTag(),null); tag.putInt("schema_version",99); var root=new CompoundTag(); root.put("data",tag);
        Path file=directory.resolve(ConstructionSavedData.DATA_NAME+".dat"); NbtIo.writeCompressed(root,file); byte[] original=Files.readAllBytes(file);
        var storage=storage(); assertThrows(IllegalStateException.class,()->ConstructionSavedData.getOrCreate(storage,directory));
        storage.save(); IOUtilities.waitUntilIOWorkerComplete(); assertArrayEquals(original,Files.readAllBytes(file));
    }
}

package dev.livingkingdoms.rpg.persistence;

import dev.livingkingdoms.rpg.domain.Contract;
import dev.livingkingdoms.rpg.domain.ContractBoard;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
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

class RpgSavedDataTest {
    @TempDir Path directory;

    private DimensionDataStorage storage() {
        SharedConstants.tryDetectVersion();
        return new DimensionDataStorage(directory.toFile(), null, null);
    }

    @Test void saveAndReopenPreservesRankAndBoards() {
        var storage = storage();
        var data = RpgSavedData.getOrCreate(storage, directory);
        UUID kingdom = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
        assertEquals(250, data.addKingdomXp(kingdom, 250));
        assertEquals(250, data.addKingdomXp(kingdom, -5), "Non-positive XP is ignored");
        var board = ContractBoard.empty().refresh(200, 28, 5, 3, true, true).progress(Contract.Kind.SLAY, 4);
        board = board.claimDaily(board.daily().size() - 1 == -1 ? 0 : 0);
        assertTrue(data.putBoard(player, board));
        var withoutWeekly = new ContractBoard(3, List.of(Contract.of(Contract.Kind.SUPPLY, "", 1)), 0, null, 7, 2, true);
        assertTrue(data.putBoard(other, withoutWeekly));
        assertTrue(data.isDirty());
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var reopened = RpgSavedData.getOrCreate(storage(), directory);
        assertEquals(250, reopened.kingdomXp(kingdom));
        assertEquals(0, reopened.kingdomXp(UUID.randomUUID()));
        assertEquals(board, reopened.board(player));
        assertEquals(withoutWeekly, reopened.board(other));
        assertEquals(ContractBoard.empty(), reopened.board(UUID.randomUUID()));
        assertFalse(reopened.isDirty());
    }

    @Test void futureSchemaCannotBeOverwritten() throws Exception {
        var tag = new RpgSavedData().save(new CompoundTag(), null);
        tag.putInt("schema_version", RpgSavedData.SCHEMA_VERSION + 1);
        var root = new CompoundTag(); root.put("data", tag);
        Path file = directory.resolve(RpgSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        byte[] original = Files.readAllBytes(file);
        var storage = storage();
        assertThrows(IllegalStateException.class, () -> RpgSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void malformedRecordsAreRejected() {
        var tag = new RpgSavedData().save(new CompoundTag(), null);
        var bad = new CompoundTag(); bad.putUUID("settlement", UUID.randomUUID()); bad.putLong("xp", -1);
        var list = new net.minecraft.nbt.ListTag(); list.add(bad); tag.put("kingdoms", list);
        assertThrows(IllegalArgumentException.class, () -> RpgSavedData.load(tag, null));
        var missing = new CompoundTag(); missing.putInt("schema_version", 1);
        assertThrows(IllegalArgumentException.class, () -> RpgSavedData.load(missing, null));
    }
}

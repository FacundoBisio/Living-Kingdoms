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
    @Test void schemaTwoKeepsBondsExpeditionsAndPendingLoot() {
        var storage = storage();
        var data = RpgSavedData.getOrCreate(storage, directory);
        UUID citizen = UUID.randomUUID(), settlement = UUID.randomUUID(), player = UUID.randomUUID();
        var bond = dev.livingkingdoms.rpg.domain.Bond.empty().talked(4).fulfilled(4);
        assertTrue(data.putBond(citizen, bond));
        var trip = dev.livingkingdoms.rpg.domain.Expedition.start(citizen, settlement, player, dev.livingkingdoms.rpg.domain.Expedition.Kind.EXPLORE, 50);
        assertTrue(data.startExpedition(trip));
        assertFalse(data.startExpedition(trip), "A citizen travels once at a time");
        var stack = new CompoundTag(); stack.putString("id", "minecraft:emerald"); stack.putInt("count", 5);
        assertEquals(1, data.addPending(player, List.of(stack)));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        var reopened = RpgSavedData.getOrCreate(storage(), directory);
        assertEquals(bond, reopened.bond(citizen));
        assertEquals(trip, reopened.expedition(citizen).orElseThrow());
        assertEquals(List.of(stack), reopened.pending(player));
        assertEquals(List.of(stack), reopened.takePending(player));
        assertTrue(reopened.pending(player).isEmpty() && reopened.endExpedition(citizen) && reopened.expedition(citizen).isEmpty());
    }

    @Test void schemaOneMigratesWithEmptyFriendshipData() {
        var tag = new CompoundTag(); tag.putInt("schema_version", 1);
        var kingdom = new CompoundTag(); UUID id = UUID.randomUUID(); kingdom.putUUID("settlement", id); kingdom.putLong("xp", 120);
        var kingdoms = new net.minecraft.nbt.ListTag(); kingdoms.add(kingdom);
        tag.put("kingdoms", kingdoms); tag.put("boards", new net.minecraft.nbt.ListTag());
        var data = RpgSavedData.load(tag, null);
        assertEquals(120, data.kingdomXp(id));
        assertTrue(data.expeditions().isEmpty() && data.bond(UUID.randomUUID()).value() == 0);
        assertTrue(data.isDirty(), "Migrated data is rewritten as the current schema");
        assertEquals(RpgSavedData.SCHEMA_VERSION, data.save(new CompoundTag(), null).getInt("schema_version"));
    }

    @Test void expeditionsPerSettlementAreCapped() {
        var data = new RpgSavedData(); UUID settlement = UUID.randomUUID();
        for (int i = 0; i < dev.livingkingdoms.rpg.domain.Expedition.MAX_PER_SETTLEMENT; i++)
            assertTrue(data.startExpedition(dev.livingkingdoms.rpg.domain.Expedition.start(UUID.randomUUID(), settlement, UUID.randomUUID(), dev.livingkingdoms.rpg.domain.Expedition.Kind.FORAGE, 0)));
        assertFalse(data.startExpedition(dev.livingkingdoms.rpg.domain.Expedition.start(UUID.randomUUID(), settlement, UUID.randomUUID(), dev.livingkingdoms.rpg.domain.Expedition.Kind.FORAGE, 0)));
    }

    @Test void schemaThreeKeepsDecorTechniquesAndWalls() {
        var storage = storage();
        var data = RpgSavedData.getOrCreate(storage, directory);
        UUID settlement = UUID.randomUUID(), player = UUID.randomUUID();
        data.markDecor(settlement, dev.livingkingdoms.rpg.domain.TownDecor.LANTERNS.bit());
        data.markDecor(settlement, dev.livingkingdoms.rpg.domain.TownDecor.FOUNTAIN.bit());
        assertTrue(data.learn(player, dev.livingkingdoms.rpg.domain.Technique.DRILL));
        assertFalse(data.learn(player, dev.livingkingdoms.rpg.domain.Technique.DRILL), "A technique is learned once");
        assertTrue(data.learn(player, dev.livingkingdoms.rpg.domain.Technique.HAGGLING));
        var wall = dev.livingkingdoms.rpg.domain.Fortification.none(40).upgrade().progress(17);
        assertTrue(data.putWall(settlement, wall));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        var reopened = RpgSavedData.getOrCreate(storage(), directory);
        assertEquals(dev.livingkingdoms.rpg.domain.TownDecor.LANTERNS.bit() | dev.livingkingdoms.rpg.domain.TownDecor.FOUNTAIN.bit(), reopened.decor(settlement));
        assertEquals(java.util.Set.of(dev.livingkingdoms.rpg.domain.Technique.DRILL, dev.livingkingdoms.rpg.domain.Technique.HAGGLING), reopened.techniques(player));
        assertEquals(wall, reopened.wall(settlement).orElseThrow());
        assertEquals(List.of(settlement), reopened.walledSettlements());
        assertTrue(reopened.techniques(UUID.randomUUID()).isEmpty() && reopened.decor(UUID.randomUUID()) == 0);
    }

    @Test void schemaTwoMigratesWithoutDecorTechniquesOrWalls() {
        var tag = new RpgSavedData().save(new CompoundTag(), null);
        tag.putInt("schema_version", 2);
        tag.remove("decor"); tag.remove("techniques"); tag.remove("walls");
        var data = RpgSavedData.load(tag, null);
        assertTrue(data.walledSettlements().isEmpty() && data.decor(UUID.randomUUID()) == 0);
        assertTrue(data.isDirty(), "Migrated data is rewritten as schema 3");
        assertEquals(3, data.save(new CompoundTag(), null).getInt("schema_version"));
    }
}

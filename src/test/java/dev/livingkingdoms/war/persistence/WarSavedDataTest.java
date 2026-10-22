package dev.livingkingdoms.war.persistence;

import dev.livingkingdoms.war.domain.KingdomStance;
import dev.livingkingdoms.war.domain.RenownLedger;
import dev.livingkingdoms.war.domain.RenownRank;
import dev.livingkingdoms.war.domain.WarFront;
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

class WarSavedDataTest {
    @TempDir Path directory;

    private DimensionDataStorage storage() {
        SharedConstants.tryDetectVersion();
        return new DimensionDataStorage(directory.toFile(), null, null);
    }

    @Test void saveAndReopenPreservesFrontsCampaignsAndRenown() {
        var storage = storage();
        var data = WarSavedData.getOrCreate(storage, directory);
        UUID hostile = UUID.randomUUID(), independent = UUID.randomUUID(), garrison = UUID.randomUUID();
        UUID party = UUID.randomUUID(), target = UUID.randomUUID(), player = UUID.randomUUID();
        WarFront front = WarFront.initial(KingdomStance.HOSTILE).raidLaunched(50, 400).raidUnanswered()
                .garrisonSpawned(garrison).withStandardsPlaced();
        assertTrue(data.putFront(hostile, front));
        assertTrue(data.putFront(independent, WarFront.initial(KingdomStance.INDEPENDENT)));
        assertTrue(data.addCampaign(party, new WarSavedData.Campaign(hostile, target, 50)));
        assertFalse(data.addCampaign(party, new WarSavedData.Campaign(hostile, target, 60)), "A party marches once");
        assertTrue(data.putLedger(player, RenownLedger.empty().add(150).rewardedThrough(RenownRank.SQUIRE)));
        assertTrue(data.isDirty());
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        var reopened = WarSavedData.getOrCreate(storage(), directory);
        assertEquals(List.of(hostile, independent), reopened.frontIds());
        assertEquals(front, reopened.front(hostile).orElseThrow());
        assertTrue(reopened.hostile(hostile) && !reopened.hostile(independent) && !reopened.hostile(UUID.randomUUID()));
        assertEquals(hostile, reopened.garrisonOwner(garrison).orElseThrow());
        assertEquals(new WarSavedData.Campaign(hostile, target, 50), reopened.campaign(party).orElseThrow());
        assertTrue(reopened.hasCampaignAgainst(target));
        assertEquals(new RenownLedger(150, RenownRank.SQUIRE), reopened.ledger(player));
        assertEquals(RenownLedger.empty(), reopened.ledger(UUID.randomUUID()));
        assertFalse(reopened.isDirty());
    }

    @Test void garrisonIndexFollowsFrontReplacement() {
        var data = new WarSavedData();
        UUID kingdom = UUID.randomUUID(), first = UUID.randomUUID();
        WarFront front = WarFront.initial(KingdomStance.HOSTILE).garrisonSpawned(first);
        data.putFront(kingdom, front);
        assertEquals(kingdom, data.garrisonOwner(first).orElseThrow());
        data.putFront(kingdom, front.garrisonCleared(true, 0, 0));
        assertTrue(data.garrisonOwner(first).isEmpty());
        UUID second = UUID.randomUUID();
        data.putFront(kingdom, front.garrisonCleared(true, 0, 0).garrisonSpawned(second));
        assertThrows(IllegalArgumentException.class,
                () -> data.putFront(UUID.randomUUID(), WarFront.initial(KingdomStance.HOSTILE).garrisonSpawned(second)),
                "One party cannot garrison two kingdoms");
        assertTrue(data.removeCampaign(UUID.randomUUID()).isEmpty());
    }

    @Test void futureSchemaCannotBeOverwritten() throws Exception {
        var tag = new WarSavedData().save(new CompoundTag(), null);
        tag.putInt("schema_version", WarSavedData.SCHEMA_VERSION + 1);
        var root = new CompoundTag();
        root.put("data", tag);
        Path file = directory.resolve(WarSavedData.DATA_NAME + ".dat");
        NbtIo.writeCompressed(root, file);
        byte[] original = Files.readAllBytes(file);
        var storage = storage();
        assertThrows(IllegalStateException.class, () -> WarSavedData.getOrCreate(storage, directory));
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(original, Files.readAllBytes(file));
        assertThrows(IllegalArgumentException.class, () -> WarSavedData.load(tag, null));
    }

    @Test void malformedEntriesAreRejected() {
        var data = new WarSavedData();
        data.putFront(UUID.randomUUID(), WarFront.initial(KingdomStance.HOSTILE));
        CompoundTag saved = data.save(new CompoundTag(), null);
        saved.getList("fronts", 10).getCompound(0).putString("stance", "peaceful");
        assertThrows(IllegalArgumentException.class, () -> WarSavedData.load(saved, null));
        CompoundTag missing = new WarSavedData().save(new CompoundTag(), null);
        missing.remove("renown");
        assertThrows(IllegalArgumentException.class, () -> WarSavedData.load(missing, null));
    }
}

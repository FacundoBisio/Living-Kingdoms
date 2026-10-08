package dev.livingkingdoms.settlement.persistence;

import dev.livingkingdoms.settlement.domain.Settlement;
import dev.livingkingdoms.settlement.domain.Territory;
import dev.livingkingdoms.structure.ArchitectureStyle;
import dev.livingkingdoms.structure.BuildingKind;
import dev.livingkingdoms.structure.PlotBounds;
import dev.livingkingdoms.structure.SettlementLayoutMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementLayoutPersistenceTest {
    private static Settlement settlement() {
        return Settlement.founding(UUID.randomUUID(), new Territory("minecraft:overworld", 0, 72, 0, 48), 5);
    }

    private static SettlementLayoutMetadata layout() {
        return new SettlementLayoutMetadata(ArchitectureStyle.TAIGA, List.of(
                new SettlementLayoutMetadata.Building(BuildingKind.CORE, ResourceLocation.parse("livingkingdoms:allied/plains/core"),
                        new BlockPos(-6, 71, -9), Rotation.NONE, new PlotBounds(-6, -9, 6, 3), new BlockPos(0, 71, 4)),
                new SettlementLayoutMetadata.Building(BuildingKind.HOUSE, ResourceLocation.parse("livingkingdoms:allied/plains/house"),
                        new BlockPos(12, 74, 6), Rotation.CLOCKWISE_90, new PlotBounds(12, 6, 16, 10), new BlockPos(11, 74, 8))),
                List.of(new BlockPos(6, 71, 0)), List.of(new BlockPos(7, 70, 0), new BlockPos(8, 71, 0)));
    }

    @Test void legacySchemaOneLoadsWithoutLayoutAndRetainsAllIdentityFields() {
        var old = new SettlementSavedData();
        var settlement = settlement();
        old.add(settlement);
        CompoundTag saved = old.save(new CompoundTag(), null);
        assertEquals(1, saved.getInt("schema_version"));
        assertFalse(saved.getList("settlements", 10).getCompound(0).contains("layout"));
        var loaded = SettlementSavedData.load(saved, null);
        assertEquals(List.of(settlement), loaded.settlements());
        assertTrue(loaded.layout(settlement.id()).isEmpty());
        assertEquals(saved, loaded.save(new CompoundTag(), null));
    }

    @Test void optionalMetadataRoundTripsRotationsElevationsPathsAndBiomeStyle() {
        var data = new SettlementSavedData();
        var settlement = settlement();
        var layout = layout();
        data.add(settlement, layout);
        var loaded = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(settlement, loaded.get(settlement.id()).orElseThrow());
        assertEquals(layout, loaded.layout(settlement.id()).orElseThrow());
        assertFalse(loaded.isDirty());
    }

    @Test void invalidMetadataCannotPartiallyAddSettlement() {
        var data = new SettlementSavedData();
        var settlement = Settlement.founding(UUID.randomUUID(), new Territory("minecraft:overworld", 0, 72, 0, 5), 5);
        assertThrows(IllegalArgumentException.class, () -> data.add(settlement, layout()));
        assertTrue(data.settlements().isEmpty());
        assertFalse(data.isDirty());
    }

    @Test void corruptOptionalMetadataIsRejectedInsteadOfSilentlyDropped() {
        var data = new SettlementSavedData();
        var settlement = settlement();
        data.add(settlement, layout());
        var tag = data.save(new CompoundTag(), null);
        tag.getList("settlements", 10).getCompound(0).getCompound("layout").putString("style", "FUTURE_UNKNOWN_STYLE");
        assertThrows(IllegalArgumentException.class, () -> SettlementSavedData.load(tag, null));
    }
}

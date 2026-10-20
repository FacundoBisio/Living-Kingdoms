package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CitizenVisualPayloadTest {
    private static final String[] TARGETS = {"", "town_hall", "lumberjack_hut", "a_".repeat(24)};
    @Test void everyProfessionStateAndUtfNameRoundTripsWithinFixedPacketBudget() {
        for (var profession : ProfessionType.values()) for (var state : WorkState.values()) for (var target : TARGETS) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
            try {
                var data = new CitizenPresentation(19, UUID.randomUUID(), "Á".repeat(80), 100, profession,
                        100, state, true, false, true, "lumberjack_hut", 1_000_000_000L, target);
                var packet = new CitizenVisualPayloads.Snapshot(data, true, false);
                CitizenVisualPayloads.Snapshot.CODEC.encode(buffer, packet);
                assertTrue(buffer.readableBytes() < 512);
                assertEquals(packet, CitizenVisualPayloads.Snapshot.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void rejectsUnboundedOrInvalidServerProjectionsBeforeCaching() {
        assertThrows(IllegalArgumentException.class, () -> new CitizenPresentation(1, UUID.randomUUID(), "a".repeat(81),
                1, ProfessionType.MAYOR, 1, WorkState.IDLE, true, false, false, "", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new CitizenPresentation(1, UUID.randomUUID(), "Ada",
                0, ProfessionType.MAYOR, 1, WorkState.IDLE, true, false, false, "", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new CitizenPresentation(1, UUID.randomUUID(), "Ada",
                1, ProfessionType.MAYOR, 1, WorkState.IDLE, true, false, false, "../../other", 0, ""));
    }
    @Test void rejectsInvalidActivityTargets() {
        for (String target : new String[]{"Town_hall", "a".repeat(49), "town-hall", "house.", "../house", "farm 2"})
            assertThrows(IllegalArgumentException.class, () -> new CitizenPresentation(1, UUID.randomUUID(), "Ada",
                    1, ProfessionType.BUILDER, 1, WorkState.WORKING, true, false, false, "", 0, target), target);
        assertThrows(NullPointerException.class, () -> new CitizenPresentation(1, UUID.randomUUID(), "Ada",
                1, ProfessionType.BUILDER, 1, WorkState.WORKING, true, false, false, "", 0, null));
    }
    @Test void untrackingPayloadRemovesOnlyTheUuidThatLeftVisibility() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
        try {
            var packet = new CitizenVisualPayloads.Remove(UUID.randomUUID());
            CitizenVisualPayloads.Remove.CODEC.encode(buffer, packet);
            assertEquals(16, buffer.readableBytes());
            assertEquals(packet, CitizenVisualPayloads.Remove.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
}

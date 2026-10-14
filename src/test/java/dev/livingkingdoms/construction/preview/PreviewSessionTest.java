package dev.livingkingdoms.construction.preview;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PreviewSessionTest {
    @Test void capabilityRejectsCrossPlayerCrossDimensionExpiredAndReplayedOldTokens() {
        var token = UUID.randomUUID(); var player = UUID.randomUUID();
        var session = new PreviewSession(token,player,"minecraft:overworld",2500);
        assertTrue(session.accepts(token,player,"minecraft:overworld",100));
        assertTrue(session.accepts(token,player,"minecraft:overworld",2499));
        assertFalse(session.accepts(token,UUID.randomUUID(),"minecraft:overworld",100));
        assertFalse(session.accepts(UUID.randomUUID(),player,"minecraft:overworld",100));
        assertFalse(session.accepts(token,player,"minecraft:the_nether",100));
        assertFalse(session.accepts(token,player,"minecraft:overworld",2500));
        assertFalse(session.accepts(token,player,"minecraft:overworld",99));
    }
}

package dev.livingkingdoms;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every class that keeps strong static per-world state must expose a static no-arg cleanup for ServerStoppingEvent. */
class ServerStopCleanupTest {
    private static final List<String> OWNERS = List.of(
        "dev.livingkingdoms.construction.ConstructionService", "dev.livingkingdoms.construction.ConstructionStages",
        "dev.livingkingdoms.construction.persistence.ConstructionSavedData", "dev.livingkingdoms.economy.BuildingHearts",
        "dev.livingkingdoms.rpg.RpgPerf");

    @Test
    void ownersExposeStaticCleanup() throws Exception {
        for (var name : OWNERS) {
            boolean found = false;
            for (var m : Class.forName(name).getDeclaredMethods())
                found |= (m.getName().equals("clear") || m.getName().equals("reset")) && m.getParameterCount() == 0
                    && Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers());
            assertTrue(found, name + " needs a public static clear()/reset()");
        }
    }
}

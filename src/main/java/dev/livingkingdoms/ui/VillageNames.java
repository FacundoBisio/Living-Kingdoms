package dev.livingkingdoms.ui;

import dev.livingkingdoms.settlement.domain.Settlement;
import java.util.UUID;

public final class VillageNames {
    private static final String[] ROOTS = {"Oak", "Willow", "River", "Alder", "Briar", "Ash", "Fair", "Stone", "Green", "Raven", "Silver", "Amber"};
    private static final String[] ENDINGS = {"haven", "ford", "brook", "wick", "stead", "wood", "vale", "field"};
    private VillageNames() {}
    public static String generated(UUID id) {
        return ROOTS[Math.floorMod(id.getMostSignificantBits(), ROOTS.length)]
                + ENDINGS[Math.floorMod(id.getLeastSignificantBits(), ENDINGS.length)];
    }
    /** Old debug-derived default names get a display alias; their saved records and IDs remain intact. */
    public static String display(Settlement settlement) {
        return settlement.name().matches("Haven [0-9a-f]{8}") ? generated(settlement.id()) : settlement.name();
    }
}

package dev.livingkingdoms.chronicle;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;

/** Vanilla advancement progress is the persistent one-time receipt, with no custom reward replay. */
public final class ChronicleAdvancements {
    private static final Set<String> KEYS = Set.of("converted_village", "first_warehouse", "all_professions", "first_upgrade", "first_trade", "development3");
    private ChronicleAdvancements() {}
    public static boolean grant(ServerPlayer player, String key) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Advancements require server authority");
        if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown chronicle advancement");
        var advancement = player.server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath("livingkingdoms", key));
        return advancement != null && player.getAdvancements().award(advancement, "achieved");
    }
}

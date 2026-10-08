package dev.livingkingdoms.advancement;

import dev.livingkingdoms.LivingKingdoms;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Future tree identifiers only; only FIRST_KINGDOM has an advancement definition and trigger. */
public enum KingdomMilestone {
    FIRST_KINGDOM, FIRST_CITIZEN, FIRST_UPGRADE, FIRST_PILLAGER_CAMP, FIRST_LIBERATION, FIRST_FORTRESS, SURVIVE_RAID;
    public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath(LivingKingdoms.MOD_ID,name().toLowerCase(java.util.Locale.ROOT)); }
    /** Vanilla player advancement data supplies the persistent one-time receipt. Founding OR conversion qualifies. */
    public static boolean awardFirstKingdom(ServerPlayer player) {
        var advancement=player.server.getAdvancements().get(FIRST_KINGDOM.id());
        return advancement!=null && player.getAdvancements().award(advancement,"established");
    }
}

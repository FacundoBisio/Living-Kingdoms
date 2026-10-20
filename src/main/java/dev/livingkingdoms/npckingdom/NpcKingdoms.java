package dev.livingkingdoms.npckingdom;

import dev.livingkingdoms.npckingdom.persistence.NpcKingdomSavedData;
import dev.livingkingdoms.settlement.domain.Settlement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Single server-side independence gate shared by onboarding, quests, economy and UI entry points. */
public final class NpcKingdoms {
    public static final String VISITOR_KEY = "npckingdom.livingkingdoms.visitor";
    private NpcKingdoms() {}

    public static boolean isNpc(MinecraftServer server, UUID settlement) {
        return settlement != null && NpcKingdomSavedData.get(server).isNpc(settlement);
    }

    public static String ruler(MinecraftServer server, UUID settlement) {
        return NpcKingdomSavedData.get(server).ruler(settlement).orElse("?");
    }

    public static Component visitorMessage(MinecraftServer server, Settlement settlement) {
        return Component.translatable(VISITOR_KEY, dev.livingkingdoms.ui.VillageNames.display(settlement), ruler(server, settlement.id()));
    }

    /** Sends the visitor notice when the settlement is NPC-ruled; returns whether it was. */
    public static boolean refuseVisitor(ServerPlayer player, Settlement settlement) {
        if (!isNpc(player.server, settlement.id())) return false;
        player.displayClientMessage(visitorMessage(player.server, settlement), false);
        return true;
    }
}

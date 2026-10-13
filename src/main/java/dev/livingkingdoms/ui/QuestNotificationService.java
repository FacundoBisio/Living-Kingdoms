package dev.livingkingdoms.ui;

import dev.livingkingdoms.quest.domain.QuestNotice;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class QuestNotificationService {
    private QuestNotificationService() {}
    public static void send(MinecraftServer server,QuestNotice.Transition transition) {
        if(!server.isSameThread()) throw new IllegalStateException("Quest notices require server authority");
        var player=server.getPlayerList().getPlayer(transition.player());
        if(player!=null) deliver(player,transition);
    }
    /** Explicit recipient still must own the transition; also supports server-side player implementations. */
    public static void deliver(net.minecraft.server.level.ServerPlayer player,QuestNotice.Transition transition) {
        if(!player.server.isSameThread()) throw new IllegalStateException("Quest notices require server authority");
        if(!player.getUUID().equals(transition.player())) return;
        var server=player.server;
        {
            String name=dev.livingkingdoms.settlement.persistence.SettlementSavedData.get(server).get(transition.notice().settlement()).map(VillageNames::display).orElse("");
            try {PacketDistributor.sendToPlayer(player,new UiPayloads.QuestNotification(transition.notice(),name));}
            catch(RuntimeException unavailable) {com.mojang.logging.LogUtils.getLogger().warn("Could not deliver quest notice to {}; gameplay receipt is retained",player.getUUID(),unavailable);}
        }
    }
}

package dev.livingkingdoms.ui;

import dev.livingkingdoms.citizen.CitizenService;
import dev.livingkingdoms.citizen.persistence.CitizenSavedData;
import dev.livingkingdoms.quest.persistence.QuestSavedData;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.WeakHashMap;

/** One indexed territory query per player/second. Identical snapshots never produce duplicate packets. */
public final class KingdomHudService {
    private static final Map<ServerPlayer,CompoundTag> LAST_SENT=new WeakHashMap<>();
    private KingdomHudService() {}
    public static CompoundTag snapshot(ServerPlayer player) {
        var tag=new CompoundTag();
        if(!player.isAlive() || player.isSpectator()) return tag;
        var pos=player.blockPosition(); var quests=QuestSavedData.get(player.server);
        SettlementSavedData.get(player.server).alliedAt(player.level().dimension().location().toString(),pos.getX(),pos.getZ())
                .ifPresent(s -> {
                    var settlement=new CompoundTag(); settlement.putString("name",VillageNames.display(s));
                    int reputation=Math.max(0,quests.reputation(player.getUUID(),s.id()));
                    settlement.putInt("reputation",reputation);
                    settlement.putString("safety",dev.livingkingdoms.defense.DefenseService.status(player.server,s.id()).name().toLowerCase(java.util.Locale.ROOT));
                    // Display bands only: no new settlement tiers, reward multipliers or progression rules.
                    settlement.putInt("level",reputation/10+1); settlement.putInt("progress",reputation%10); settlement.putInt("next",10);
                    var homes=CitizenSavedData.get(player.server).summary(s.id());
                    settlement.putInt("population",CitizenService.population(player.server,s)); settlement.putInt("housing",homes.total());
                    tag.put("settlement",settlement);
                });
        quests.tracked(player.getUUID()).ifPresent(q -> tag.put("quest",VillageUiService.questView(player,q)));
        if(!tag.isEmpty()) tag.putString("dimension",player.level().dimension().location().toString());
        return tag;
    }
    public static boolean update(ServerPlayer player) {
        var next=snapshot(player);
        if(next.equals(LAST_SENT.get(player))) return false;
        LAST_SENT.put(player,next.copy()); PacketDistributor.sendToPlayer(player,new UiPayloads.HudSnapshot(next)); return true;
    }
    public static void onTick(ServerTickEvent.Post event) {
        if(event.getServer().overworld().getGameTime()%20!=0) return;
        for(var player:event.getServer().getPlayerList().getPlayers()) update(player);
    }
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) { refresh(event); }
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) { refresh(event); }
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { refresh(event); }
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) LAST_SENT.remove(player);
    }
    private static void refresh(PlayerEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) {
            LAST_SENT.remove(player);
            update(player);
        }
    }
}

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
                    // War: hostile kingdoms replace the friendly rows; renown is the player's own military rank.
                    if(dev.livingkingdoms.war.WarService.isHostile(player.server,s.id())) {
                        var front=dev.livingkingdoms.war.persistence.WarSavedData.get(player.server).front(s.id()).orElseThrow();
                        settlement.putBoolean("hostile",true); settlement.putInt("war_strength",front.strength());
                        settlement.putString("ruler",dev.livingkingdoms.npckingdom.NpcKingdoms.ruler(player.server,s.id()));
                    }
                    if(dev.livingkingdoms.war.WarService.enabled() && dev.livingkingdoms.config.WarConfig.RENOWN_ENABLED.get()) {
                        var ledger=dev.livingkingdoms.war.persistence.WarSavedData.get(player.server).ledger(player.getUUID());
                        settlement.putString("renown_rank",ledger.rank().id()); settlement.putInt("renown",ledger.points());
                    }
                    if(!settlement.getBoolean("hostile")) {
                        long xp=dev.livingkingdoms.rpg.persistence.RpgSavedData.get(player.server).kingdomXp(s.id());
                        settlement.putInt("rank",dev.livingkingdoms.rpg.domain.KingdomRank.level(xp));
                        settlement.putLong("rank_progress",dev.livingkingdoms.rpg.domain.KingdomRank.progress(xp));
                        settlement.putLong("rank_span",dev.livingkingdoms.rpg.domain.KingdomRank.span(xp));
                        // Dynamic context rows: events, claimable contracts and the construction you stand next to.
                        var event=dev.livingkingdoms.rpg.EventService.hud(player,s.id());
                        if(!event.isEmpty()) settlement.put("event",event);
                        dev.livingkingdoms.rpg.ContractService.board(player).ifPresent(board -> {
                            int ready=(int)board.daily().stream().filter(c -> !c.claimed() && c.tracked() && c.complete()).count()
                                    +(board.weekly()!=null && !board.weekly().claimed() && board.weekly().tracked() && board.weekly().complete()?1:0);
                            if(ready>0) settlement.putInt("contracts_ready",ready);
                        });
                        dev.livingkingdoms.construction.ConstructionService.current(player.server,s.id()).ifPresent(entry -> {
                            var origin=entry.plan().buildings().getFirst().origin();
                            if(origin.distSqr(player.blockPosition())<=24*24 && entry.project().state()==dev.livingkingdoms.construction.domain.ConstructionState.BUILDING) {
                                var site=new CompoundTag(); site.putString("building",entry.project().building().name().toLowerCase(java.util.Locale.ROOT));
                                site.putInt("percent",(int)Math.round(entry.project().progress(player.server.overworld().getGameTime())*100));
                                site.putInt("helpers",dev.livingkingdoms.construction.ConstructionService.assistants(entry.project().id())+(entry.project().builderId()!=null?1:0));
                                site.putBoolean("blocked",dev.livingkingdoms.construction.ConstructionService.reason(entry.project().id()).isPresent());
                                settlement.put("site",site);
                            }
                        });
                        dev.livingkingdoms.rpg.persistence.RpgSavedData.get(player.server).wall(s.id()).filter(w -> !w.complete()).ifPresent(w ->
                                settlement.putInt("wall_building",w.tier()));
                        long now=player.server.overworld().getGameTime();
                        dev.livingkingdoms.rpg.persistence.RpgSavedData.get(player.server).expeditions().stream().filter(x -> x.settlement().equals(s.id()))
                                .min(java.util.Comparator.comparingLong(x -> x.remaining(now))).ifPresent(x -> {
                                    settlement.putInt("expeditions",(int)dev.livingkingdoms.rpg.persistence.RpgSavedData.get(player.server).expeditionsIn(s.id()));
                                    settlement.putLong("expedition_next",x.remaining(now)/20);
                                });
                        int breaches=dev.livingkingdoms.rpg.WallService.breaches(s.id());if(breaches>0) settlement.putInt("wall_breaches",breaches);
                        long alarm=dev.livingkingdoms.rpg.AlarmService.remaining(player.server,s.id());if(alarm>0) settlement.putInt("alarm",(int)(alarm/20));
                    }
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

package dev.livingkingdoms.ui;

import dev.livingkingdoms.quest.persistence.QuestSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Personal read-only journal: remote tracking is permitted, rewards and acceptance remain local. */
public final class QuestJournalService {
    private record Session(UUID token,long opened,String dimension) {}
    private static final Map<ServerPlayer,Session> SESSIONS=new WeakHashMap<>();
    private static final Map<ServerPlayer,Long> SHORTCUTS=new WeakHashMap<>();
    private QuestJournalService() {}
    public static void shortcut(ServerPlayer player,ShortcutAction action) {
        if(!player.server.isSameThread()) throw new IllegalStateException("Shortcuts require server thread");
        long now=player.server.overworld().getGameTime();
        if(!player.isAlive() || player.isSpectator() || action==ShortcutAction.HUD || now<SHORTCUTS.getOrDefault(player,0L)) return;
        SHORTCUTS.put(player,now+5);
        if(action==ShortcutAction.KINGDOM) VillageUiService.openShortcut(player); else open(player);
    }
    public static boolean open(ServerPlayer player) {
        if(!player.server.isSameThread() || !player.isAlive() || player.isSpectator()) return false;
        var session=new Session(UUID.randomUUID(),player.server.overworld().getGameTime(),player.level().dimension().location().toString());
        VillageUiService.dismiss(player);
        ManagementService.dismiss(player);dev.livingkingdoms.commerce.CommerceService.dismiss(player);
        SESSIONS.put(player,session); send(player,session,false); return true;
    }
    public static boolean owns(ServerPlayer player,UUID token) {
        var session=SESSIONS.get(player); return session!=null && session.token().equals(token);
    }
    public static void dismiss(ServerPlayer player) {SESSIONS.remove(player);}
    public static boolean refreshIfOpen(ServerPlayer player) {
        var session=SESSIONS.get(player);
        if(session==null || !player.isAlive() || player.isSpectator() || player.server.overworld().getGameTime()-session.opened()>12000
                || !session.dimension().equals(player.level().dimension().location().toString())) return false;
        send(player,session,false);return true;
    }
    public static boolean handle(ServerPlayer player,UiPayloads.Request request) {
        var session=SESSIONS.get(player);
        if(session==null || !session.token().equals(request.session())) return false;
        if(request.action()==UiPayloads.Action.CLOSE) { SESSIONS.remove(player); return true; }
        if(!player.isAlive() || player.isSpectator() || player.server.overworld().getGameTime()-session.opened()>12000
                || !session.dimension().equals(player.level().dimension().location().toString())) { SESSIONS.remove(player); var closed=new CompoundTag(); closed.putUUID("session",session.token()); closed.putString("screen","closed");
            PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(closed)); return false; }
        var data=QuestSavedData.get(player.server);
        boolean result=switch(request.action()) {
            case REFRESH, BOARD -> true;
            case TRACK -> data.track(player.getUUID(),request.quest());
            case UNTRACK -> data.untrack(player.getUUID(),request.quest());
            default -> false;
        };
        send(player,session,!result); return result;
    }
    private static void send(ServerPlayer player,Session session,boolean rejected) {
        var tag=new CompoundTag(); tag.putUUID("session",session.token()); tag.putString("screen","board"); tag.putBoolean("remote",true);
        tag.putString("settlement",""); tag.putString("hint","ui.livingkingdoms.journal.empty");
        if(rejected) tag.putString("notice","ui.livingkingdoms.journal.read_only");
        var list=new ListTag();
        for(var quest:QuestSavedData.get(player.server).quests(player.getUUID())) {
            var view=VillageUiService.questView(player,quest); view.putBoolean("accept",false); view.putBoolean("claim",false); list.add(view);
        }
        tag.put("quests",list); PacketDistributor.sendToPlayer(player,new UiPayloads.Snapshot(tag));
    }
}

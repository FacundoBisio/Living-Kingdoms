package dev.livingkingdoms.client;

import dev.livingkingdoms.config.ClientHudConfig;
import dev.livingkingdoms.quest.domain.QuestNotice;
import dev.livingkingdoms.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.*;
import net.minecraft.locale.Language;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;

/** One native toast at a time. Minecraft supplies animation, sound and coordination with other toasts. */
public final class QuestNotifications {
    private static final Object TOKEN=new Object();
    private static final QuestNoticeQueue QUEUE=new QuestNoticeQueue(8);
    private static final LinkedHashMap<UUID,String> NAMES=new LinkedHashMap<>();
    private static NoticeToast current;
    private QuestNotifications() {}
    static void receive(UiPayloads.QuestNotification payload) {
        if(Minecraft.getInstance().player==null || !ClientHudConfig.QUEST_NOTIFICATIONS.get()) return;
        if(QUEUE.offer(payload.notice())) {
            if(!NAMES.containsKey(payload.notice().settlement()) && NAMES.size()>=16) NAMES.remove(NAMES.keySet().iterator().next());
            NAMES.put(payload.notice().settlement(),payload.settlementName());
        }
    }
    public static void tick(ClientTickEvent.Post event) {
        var client=Minecraft.getInstance();
        if(client.player==null || client.level==null || !ClientHudConfig.QUEST_NOTIFICATIONS.get()) {clear();return;}
        if(current!=null && client.getToasts().getToast(NoticeToast.class,TOKEN)!=null) return;
        current=null;
        if(QUEUE.empty()) return;
        QUEUE.poll().ifPresent(notice-> {current=new NoticeToast(notice,NAMES.getOrDefault(notice.settlement(),""));client.getToasts().addToast(current);});
    }
    private static void clear() {QUEUE.clear();NAMES.clear();if(current!=null) current.closed=true;current=null;}
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {clear();}
    static void reload() {if(current!=null) current.invalidate();}
    private record Line(FormattedCharSequence text,int color) {}
    private static final class NoticeToast implements Toast {
        final QuestNotice notice;
        final String realm;
        final ItemStack icon;
        List<List<Line>> pages=List.of();
        Language language;
        int width=240,height=96,cachedWidth=-1;
        boolean closed;
        NoticeToast(QuestNotice notice,String realm) {
            this.notice=notice;this.realm=realm;
            icon=new ItemStack(switch(notice.template().type()) {
                case RESOURCE_DELIVERY->notice.template().id().contains("food")?Items.WHEAT:Items.IRON_INGOT;
                case HOSTILE_PARTY_ELIMINATION->Items.IRON_SWORD;
                case MEET_NPC->Items.BOOK;
                case RETURN_TO_SETTLEMENT->Items.MAP;
            });
            layout(Minecraft.getInstance().getWindow().getGuiScaledWidth());
        }
        void invalidate() {cachedWidth=-1;}
        @Override public Object getToken() {return TOKEN;}
        @Override public int width() {return width;}
        @Override public int height() {return height;}
        @Override public int slotCount() {return 4;}
        void layout(int screenWidth) {
            width=Math.clamp(screenWidth/2,160,256);var lines=new ArrayList<Line>();var font=Minecraft.getInstance().font;
            int accent=notice.kind()==QuestNotice.Kind.OBJECTIVE_COMPLETED?0xFFFFD27C:0xFFA6E38A;
            var title=Component.translatable("notification.livingkingdoms."+notice.kind().name().toLowerCase(Locale.ROOT));
            for(var text:font.split(title,width-34)) lines.add(new Line(text,accent));
            for(var text:font.split(Component.translatable(notice.template().titleKey()),width-34)) lines.add(new Line(text,0xFFF5F2E9));
            boolean pending=notice.kind()==QuestNotice.Kind.OBJECTIVE_COMPLETED;
            Component reward=notice.emeralds()+notice.reputation()>0?Component.translatable("notification.livingkingdoms."+(pending?"pending":"obtained"),notice.emeralds(),notice.reputation())
                    :Component.translatable("notification.livingkingdoms.progress");
            for(var text:font.split(reward,width-34)) lines.add(new Line(text,0xFFE0D9C9));
            if(pending) for(var text:font.split(Component.translatable("notification.livingkingdoms.claim",realm),width-34)) lines.add(new Line(text,0xFFE0D9C9));
            // Paginate whole words at small GUI sizes; no ellipsis or scaled text.
            pages=DialoguePages.of(lines,9);height=12+Math.min(9,lines.size())*11;
            cachedWidth=screenWidth;language=Language.getInstance();
        }
        @Override public Visibility render(GuiGraphics g,ToastComponent toasts,long elapsed) {
            if(closed) return Visibility.HIDE;
            if(cachedWidth!=g.guiWidth() || language!=Language.getInstance()) layout(g.guiWidth());
            int accent=notice.kind()==QuestNotice.Kind.OBJECTIVE_COMPLETED?0xFFD3AA53:0xFF6DA862;
            g.fill(1,1,width-1,height-1,0xEE151C21);
            g.fill(2,0,width-2,1,accent);g.fill(2,height-1,width-2,height,accent);
            g.fill(0,2,1,height-2,accent);g.fill(width-1,2,width,height-2,accent);
            g.fill(1,1,3,3,accent);g.fill(width-3,height-3,width-1,height-1,accent);
            g.renderItem(icon,5,8);
            double duration=Math.max(6000,pages.size()*3000L)*toasts.getNotificationDisplayTimeMultiplier();
            var page=pages.get(Math.min(pages.size()-1,(int)(elapsed/Math.max(1,duration/pages.size()))));int y=6;
            for(var line:page) {g.drawString(toasts.getMinecraft().font,line.text(),27,y,line.color(),false);y+=11;}
            return elapsed<duration?Visibility.SHOW:Visibility.HIDE;
        }
    }
}

package dev.livingkingdoms.ui;

import dev.livingkingdoms.quest.domain.QuestNotice;
import java.util.*;

/** Bounded delivery deduplication. Persisted quest transitions provide the cross-session receipt. */
public final class QuestNoticeQueue {
    private final int capacity;
    private final ArrayDeque<QuestNotice> pending=new ArrayDeque<>();
    private final LinkedHashSet<QuestNotice.Key> seen=new LinkedHashSet<>();
    public QuestNoticeQueue(int capacity) {if(capacity<1 || capacity>16) throw new IllegalArgumentException("Invalid notice capacity");this.capacity=capacity;}
    public boolean offer(QuestNotice notice) {
        if(!seen.add(notice.key())) return false;
        while(seen.size()>128) seen.remove(seen.iterator().next());
        // A claim arriving in the same batch supersedes its unshown ready message.
        if(notice.kind()!=QuestNotice.Kind.OBJECTIVE_COMPLETED) pending.removeIf(old->old.quest().equals(notice.quest()));
        if(pending.size()==capacity) pending.removeFirst();
        pending.addLast(notice);return true;
    }
    public Optional<QuestNotice> poll() {return Optional.ofNullable(pending.pollFirst());}
    public boolean empty() {return pending.isEmpty();}
    public int size() {return pending.size();}
    public void clear() {pending.clear();seen.clear();}
}

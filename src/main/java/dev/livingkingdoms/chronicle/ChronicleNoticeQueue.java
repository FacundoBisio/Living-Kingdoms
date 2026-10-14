package dev.livingkingdoms.chronicle;

import java.util.*;

/** Eight pending native toasts; recent packet IDs deduplicated within the current connection. */
public final class ChronicleNoticeQueue {
    public static final int CAPACITY = 8, SEEN_LIMIT = 128;
    private final ArrayDeque<ChroniclePayloads.Notice> pending = new ArrayDeque<>();
    private final LinkedHashSet<UUID> seen = new LinkedHashSet<>();
    public boolean offer(ChroniclePayloads.Notice notice) {
        if (!seen.add(notice.event().id())) return false;
        while (seen.size() > SEEN_LIMIT) seen.remove(seen.iterator().next());
        if (pending.size() == CAPACITY) pending.removeFirst();
        pending.addLast(notice); return true;
    }
    public Optional<ChroniclePayloads.Notice> poll() { return Optional.ofNullable(pending.pollFirst()); }
    public int size() { return pending.size(); }
    public void clear() { pending.clear(); seen.clear(); }
}

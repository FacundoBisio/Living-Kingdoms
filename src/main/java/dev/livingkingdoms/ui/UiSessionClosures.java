package dev.livingkingdoms.ui;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/** Connection-local recent cancellations. Access on the client thread; never persisted. */
public final class UiSessionClosures {
    private final int limit;
    private final LinkedHashSet<UUID> closed=new LinkedHashSet<>();
    public UiSessionClosures(int limit) {
        if(limit<1) throw new IllegalArgumentException("Closure history must be bounded above zero");
        this.limit=limit;
    }
    public void close(UUID session) {
        Objects.requireNonNull(session);
        closed.remove(session);
        closed.add(session);
        if(closed.size()>limit) closed.remove(closed.iterator().next());
    }
    public boolean isClosed(UUID session) { return closed.contains(Objects.requireNonNull(session)); }
    public void clear() { closed.clear(); }
}

package dev.livingkingdoms.ui;

import java.util.ArrayList;
import java.util.List;

/** Pages retain every wrapped line, including a final short page. No clipping or ellipses. */
public final class DialoguePages {
    private DialoguePages() {}
    public static <T> List<List<T>> of(List<T> lines, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("A page must fit at least one line");
        var pages = new ArrayList<List<T>>();
        for (int start = 0; start < lines.size(); start += capacity)
            pages.add(List.copyOf(lines.subList(start, Math.min(lines.size(), start + capacity))));
        if (pages.isEmpty()) pages.add(List.of());
        return List.copyOf(pages);
    }
}

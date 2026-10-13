package dev.livingkingdoms.client;

import net.minecraft.client.StringSplitter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded per-screen cache; style and width participate in identity. */
public final class VillageTextCache {
    private record Key(Component text, int width) {}
    private final LinkedHashMap<Key, List<FormattedCharSequence>> lines = new LinkedHashMap<>();
    private Language language;
    public void clear() { lines.clear(); language = Language.getInstance(); }
    public List<FormattedCharSequence> wrap(StringSplitter splitter, Component text, int width) {
        if (language != Language.getInstance()) clear();
        var key = new Key(text, width);
        var existing = lines.get(key);
        if (existing != null) return existing;
        var wrapped = VillageTextFlow.wrap(splitter, text, width).stream().map(language::getVisualOrder).toList();
        if (lines.size() >= 128) lines.remove(lines.keySet().iterator().next());
        lines.put(key, wrapped);
        return wrapped;
    }
}

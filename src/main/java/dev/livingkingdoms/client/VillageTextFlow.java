package dev.livingkingdoms.client;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import java.util.List;

/** Same native word wrapping as Font.split; logical lines can be checked without OpenGL. */
public final class VillageTextFlow {
    public static final int LINE_HEIGHT = 11;
    public static final int PARAGRAPH_GAP = 5;
    private VillageTextFlow() {}

    public static List<FormattedText> wrap(StringSplitter splitter, Component text, int width) {
        return splitter.splitLines(text, Math.max(30,width), Style.EMPTY);
    }

    public static int height(int lines) { return lines * LINE_HEIGHT + PARAGRAPH_GAP; }
}

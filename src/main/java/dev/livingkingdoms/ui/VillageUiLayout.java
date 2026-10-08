package dev.livingkingdoms.ui;

/** Coordinates in Minecraft GUI units, recalculated after resize or GUI-scale changes. */
public record VillageUiLayout(int left, int top, int width, int height, int listWidth, boolean compact) {
    public static VillageUiLayout fit(int screenWidth, int screenHeight, boolean board) {
        int width = Math.min(board ? 500 : 408, screenWidth - 12);
        int height = Math.min(board ? 306 : 228, screenHeight - 12);
        boolean compact = width < 400;
        int list = board ? Math.clamp(width / 3, 98, 148) : Math.clamp(width / 3, 88, 124);
        return new VillageUiLayout((screenWidth - width) / 2, (screenHeight - height) / 2,
                width, height, list, compact);
    }

    public int contentTop() { return top + 60; }
    public int footerTop() { return top + height - 29; }
    public int contentBottom() { return footerTop() - 8; }
    public int viewport() { return contentBottom() - contentTop(); }
    public int rows() { return Math.max(1, viewport() / 34); }
    public int detailX() { return left + listWidth + 12; }
    public int detailWidth() { return width - listWidth - 24; }
    public int dialogueActionsTop() { return top + height - 56; }
}

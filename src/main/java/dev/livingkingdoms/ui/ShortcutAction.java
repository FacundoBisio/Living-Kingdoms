package dev.livingkingdoms.ui;

/** Stable translated Controls entries. Defaults are key codes, never key event comparisons. */
public enum ShortcutAction {
    KINGDOM(75,"key.livingkingdoms.kingdom"), QUESTS(74,"key.livingkingdoms.quests"), HUD(72,"key.livingkingdoms.hud");
    private final int defaultKey;
    private final String titleKey;
    ShortcutAction(int defaultKey,String titleKey) { this.defaultKey=defaultKey; this.titleKey=titleKey; }
    public int defaultKey() { return defaultKey; }
    public String titleKey() { return titleKey; }
}

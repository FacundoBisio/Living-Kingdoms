package dev.livingkingdoms.ui;

/** Upper-left GUI-scaled strip, clear of center/bottom vanilla bars and right-hand status effects. */
public record KingdomHudLayout(int x,int y,int width,int height,boolean visible) {
    public record Card(int x, int y, int width, int height, int lineCapacity) {}
    public record Cards(Card kingdom, Card quest) {}

    /** Page quotas preserve all text when boss bars or a small GUI leave less vertical room. */
    public static Cards cards(int screenWidth, int screenHeight, int bossBottom, int preferredX, int preferredY,
                              int preferredWidth, int kingdomLines, int questLines) {
        int width = Math.min(Math.max(160, preferredWidth), Math.max(80, screenWidth - 12));
        int x = Math.clamp(preferredX, 0, Math.max(0, screenWidth - width - 6));
        int bottom = screenHeight - 55, y = Math.clamp(preferredY, 0, Math.max(0, bottom - 34));
        if (x < (screenWidth + 182) / 2 && x + width > (screenWidth - 182) / 2) y = Math.max(y, bossBottom + 4);
        int panels = (kingdomLines > 0 ? 1 : 0) + (questLines > 0 ? 1 : 0);
        int budget = Math.max(0, (bottom - y - panels * 12 - (panels == 2 ? 6 : 0)) / 11);
        if (panels == 0 || budget < panels * 2) return new Cards(null, null);
        int kingdomCapacity = kingdomLines > 0 ? questLines > 0 ? Math.min(kingdomLines, Math.max(2, budget / 3)) : Math.min(kingdomLines, budget) : 0;
        int questCapacity = questLines > 0 ? Math.min(questLines, budget - kingdomCapacity) : 0;
        kingdomCapacity += Math.min(Math.max(0, kingdomLines - kingdomCapacity), Math.max(0, budget - kingdomCapacity - questCapacity));
        Card kingdom = kingdomCapacity == 0 ? null : new Card(x, y, width, kingdomCapacity * 11 + 12, kingdomCapacity);
        if (kingdom != null) y += kingdom.height() + 6;
        Card quest = questCapacity == 0 ? null : new Card(x, y, width, questCapacity * 11 + 12, questCapacity);
        return new Cards(kingdom, quest);
    }
    public static KingdomHudLayout of(int screenWidth,int screenHeight,int chatHeight,int lines) {
        return belowBossBars(screenWidth,screenHeight,0,lines);
    }
    public static KingdomHudLayout belowBossBars(int screenWidth,int screenHeight,int bossBarBottom,int lines) {
        int width=Math.min(220,screenWidth/2-12),height=lines*11+12;
        // Chat's configured height includes invisible lines when closed. Reserving that height hid
        // the entire HUD at common GUI scales. Chat/input screens already suppress rendering.
        int top=6+width>=screenWidth/2-91?Math.max(6,bossBarBottom+3):6,bottom=screenHeight-55;
        return new KingdomHudLayout(6,top,width,height,width>=96 && top+height<=bottom && lines>0);
    }
}

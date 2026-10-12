package dev.livingkingdoms.ui;

/** Upper-left GUI-scaled strip, clear of center/bottom vanilla bars and right-hand status effects. */
public record KingdomHudLayout(int x,int y,int width,int height,boolean visible) {
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

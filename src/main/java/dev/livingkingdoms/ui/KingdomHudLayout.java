package dev.livingkingdoms.ui;

/** GUI-scaled safe area above chat/vanilla bars, left of subtitles. Extremely small surfaces hide safely. */
public record KingdomHudLayout(int x,int y,int width,int height,boolean visible) {
    public static KingdomHudLayout of(int screenWidth,int screenHeight,int chatHeight,int lines) {
        int width=Math.min(220,screenWidth/2-12),height=lines*11+12;
        int bottom=screenHeight-Math.max(55,chatHeight+48),top=bottom-height;
        return new KingdomHudLayout(6,Math.max(6,top),width,height,width>=96 && top>=6 && lines>0);
    }
}

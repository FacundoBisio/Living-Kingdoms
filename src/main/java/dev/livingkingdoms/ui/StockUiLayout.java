package dev.livingkingdoms.ui;

/** Uses the common GUI-size contract; content pages and buttons never share vertical space. */
public record StockUiLayout(VillageUiLayout panel,int rows,int lines,int actionWidth) {
    public static StockUiLayout fit(int width,int height) {
        var p=VillageUiLayout.fit(width,height,true);
        return new StockUiLayout(p,Math.max(1,p.viewport()/26),Math.max(1,p.viewport()/11-1),(p.width()-p.listWidth()-28)/3);
    }
}

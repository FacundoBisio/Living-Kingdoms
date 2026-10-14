package dev.livingkingdoms.ui;

/** Two columns with room for a header and vanilla controls at small GUI sizes. */
public final class PickerPages {
    private PickerPages() {}
    public static int capacity(int viewport) {return Math.clamp((viewport-26)/24,1,3)*2;}
    public static int count(int items,int viewport) {return Math.max(1,(items+capacity(viewport)-1)/capacity(viewport));}
}

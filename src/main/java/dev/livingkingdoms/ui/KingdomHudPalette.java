package dev.livingkingdoms.ui;

/** Opaque text and a bounded dark background, including over bright terrain. */
public final class KingdomHudPalette {
    public static final int BACKGROUND = 0x101820;
    public static final int MIN_BACKGROUND_ALPHA = 192;
    public static final int DEFAULT_BACKGROUND_ALPHA = 204;
    private KingdomHudPalette() {}

    public static int text(KingdomHudPresentation.Tone tone) {
        return switch (tone) {
            case TITLE -> 0xFFFFDF9C;
            case NORMAL -> 0xFFF2F4F5;
            case MUTED -> 0xFFBDC8D4;
            case SUCCESS -> 0xFFA8E0B0;
            case WARNING -> 0xFFFFB4A0;
        };
    }
}

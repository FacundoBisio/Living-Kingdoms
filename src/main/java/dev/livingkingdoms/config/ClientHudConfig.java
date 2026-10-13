package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import dev.livingkingdoms.ui.KingdomHudPalette;

/** Per-client preference, independent of multiplayer quest and settlement authority. */
public final class ClientHudConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HUD_ENABLED;
    public static final ModConfigSpec.BooleanValue SHOW_KINGDOM, SHOW_QUEST, EXPANDED;
    public static final ModConfigSpec.IntValue X, Y, WIDTH, OPACITY;
    static {
        var builder=new ModConfigSpec.Builder();
        HUD_ENABLED=builder.comment("Show the compact settlement/tracked quest HUD. Toggle using the remappable HUD key.")
                .define("hudEnabled",true);
        SHOW_KINGDOM=builder.define("showKingdomInfo",true);
        SHOW_QUEST=builder.define("showTrackedQuest",true);
        EXPANDED=builder.comment("Include reputation bands and destination details.").define("expandedHud",false);
        X=builder.comment("GUI-unit offset, clamped to the visible screen. Use offsets to avoid other mod overlays.").defineInRange("hudX",6,0,2000);
        Y=builder.defineInRange("hudY",6,0,2000);
        WIDTH=builder.defineInRange("hudWidth",200,160,320);
        OPACITY=builder.comment("Dark background alpha; the minimum preserves readable contrast over bright terrain.")
                .defineInRange("hudBackgroundAlpha",KingdomHudPalette.DEFAULT_BACKGROUND_ALPHA,KingdomHudPalette.MIN_BACKGROUND_ALPHA,255);
        SPEC=builder.build();
    }
    private ClientHudConfig() {}
}

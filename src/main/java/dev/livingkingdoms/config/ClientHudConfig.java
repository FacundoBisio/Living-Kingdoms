package dev.livingkingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-client preference, independent of multiplayer quest and settlement authority. */
public final class ClientHudConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HUD_ENABLED;
    static {
        var builder=new ModConfigSpec.Builder();
        HUD_ENABLED=builder.comment("Show the compact settlement/tracked quest HUD. Toggle using the remappable HUD key.")
                .define("hudEnabled",true);
        SPEC=builder.build();
    }
    private ClientHudConfig() {}
}

package dev.livingkingdoms.client.citizenvisual;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Rendering preferences never influence server work or combat. */
public final class CitizenVisualConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HUMANOIDS;
    public static final ModConfigSpec.BooleanValue LABELS;
    public static final ModConfigSpec.BooleanValue WORK_ANIMATIONS;
    public static final ModConfigSpec.BooleanValue WARBAND_SOLDIERS;
    public enum ModelStyle { NATIVE, ARGON4 }
    public static final ModConfigSpec.EnumValue<ModelStyle> MODEL;
    public static final ModConfigSpec.IntValue NAME_DISTANCE;
    public static final ModConfigSpec.IntValue SECONDARY_DISTANCE;
    public static final ModConfigSpec.IntValue ACTIVITY_DISTANCE;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("citizenPresentation");
        HUMANOIDS = builder.comment("Render registered citizens using original humanoid skins.").define("humanoids", true);
        MODEL = builder.comment("Client presentation only. /kingdommodels native or argon4 switches live without changing citizens.")
                .defineEnum("model", ModelStyle.ARGON4);
        LABELS = builder.define("labels", true);
        WORK_ANIMATIONS = builder.define("workAnimations", true);
        WARBAND_SOLDIERS = builder.comment("Render warband soldiers with the Argon 4 soldier model; false keeps the vanilla vindicator look.")
                .define("warbandSoldiers", true);
        NAME_DISTANCE = builder.comment("Maximum name distance in blocks; labels obey depth and invisibility.")
                .defineInRange("nameDistance", 24, 4, 64);
        SECONDARY_DISTANCE = builder.comment("Profession and level line; zero hides it. Also capped by nameDistance.")
                .defineInRange("secondaryDistance", 12, 0, 32);
        ACTIVITY_DISTANCE = builder.comment("Current activity line; zero hides it. Also capped by nameDistance.")
                .defineInRange("activityDistance", 8, 0, 32);
        builder.pop(); SPEC = builder.build();
    }
    private CitizenVisualConfig() {}
}

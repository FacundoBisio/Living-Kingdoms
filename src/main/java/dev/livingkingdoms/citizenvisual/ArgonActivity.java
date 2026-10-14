package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.WorkState;

/** Presentation selection only. A work timeline requires an already confirmed server XP pulse. */
public final class ArgonActivity {
    public static String workClip(CitizenPresentation data, float elapsedTicks, float walkingSpeed,
            boolean sleeping, boolean riding, boolean attacking) {
        if (data == null || !data.active() || data.travelling() || sleeping || riding || attacking
                || !Float.isFinite(elapsedTicks) || !Float.isFinite(walkingSpeed) || walkingSpeed < 0
                || walkingSpeed >= .1F || elapsedTicks < 0
                || data.state() != WorkState.WORKING && data.state() != WorkState.REPAIRING) return null;
        String clip = switch (data.profession()) {
            case BUILDER, BLACKSMITH -> "work_hammer";
            case FARMER -> "farm_plant";
            case LUMBERJACK -> "lumberjack_chop";
            default -> null;
        };
        float duration = switch (data.profession()) {
            case BUILDER, BLACKSMITH -> 32;
            case FARMER -> 30;
            case LUMBERJACK -> 28;
            default -> 0;
        };
        return elapsedTicks < duration ? clip : null;
    }
    private ArgonActivity() {}
}

package dev.livingkingdoms.citizenvisual;

import dev.livingkingdoms.profession.domain.ProfessionType;
import dev.livingkingdoms.profession.domain.WorkState;
import java.util.Objects;
import java.util.UUID;

/** Small server-authored projection; target is the lowercase building kind being built or repaired, or empty. It contains no inventory, authority or writable gameplay state. */
public record CitizenPresentation(int entityId, UUID entityUuid, String name, int level,
        ProfessionType profession, int professionLevel, WorkState state, boolean active,
        boolean travelling, boolean housed, String workplace, long experience, String target) {
    public CitizenPresentation {
        Objects.requireNonNull(entityUuid); Objects.requireNonNull(name);
        Objects.requireNonNull(profession); Objects.requireNonNull(state); Objects.requireNonNull(workplace);
        Objects.requireNonNull(target);
        if (name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl)
                || level < 1 || level > 100 || professionLevel < 1 || professionLevel > 100
                || workplace.length() > 48 || !workplace.matches("[a-z_]*")
                || target.length() > 48 || !target.matches("[a-z_]*")
                || experience < 0 || experience > 1_000_000_000L)
            throw new IllegalArgumentException("Invalid citizen presentation");
    }

    public int skinVariant() { return skinVariant(entityUuid); }
    public static int skinVariant(UUID id) {
        long bits = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 23);
        return Math.floorMod(Long.hashCode(bits), 8);
    }

    /** WorkState.WORKING also means walking in existing workers: only earned XP confirms an action. */
    public boolean performedWorkSince(CitizenPresentation previous) {
        return previous != null && entityUuid.equals(previous.entityUuid) && active && !travelling
                && profession == previous.profession && experience > previous.experience
                && (state == WorkState.WORKING || state == WorkState.REPAIRING);
    }

    /** A carried prop is client-only. Guards always display their actual persisted equipment. */
    public boolean showsProfessionTool() {
        if (!active || travelling || state == WorkState.SLEEPING) return false;
        return switch (profession) {
            case MAYOR -> true;
            case FARMER, LUMBERJACK, MINER, BUILDER, BLACKSMITH, MERCHANT ->
                    state == WorkState.WORKING || state == WorkState.REPAIRING;
            default -> false;
        };
    }
}

package dev.livingkingdoms.rpg.domain;

import dev.livingkingdoms.profession.domain.ProfessionType;
import java.util.Optional;

/** Player techniques taught by Loyal residents, one per profession. Effects are applied by the runtime. */
public enum Technique {
    MASTER_SMITH(ProfessionType.BLACKSMITH), HEARTY_MEALS(ProfessionType.FARMER), MASONRY(ProfessionType.BUILDER),
    DRILL(ProfessionType.GUARD), TIMBER(ProfessionType.LUMBERJACK), PROSPECTOR(ProfessionType.MINER),
    HAGGLING(ProfessionType.MERCHANT), SHORTCUTS(ProfessionType.UNASSIGNED);

    private final ProfessionType teacher;
    Technique(ProfessionType teacher) { this.teacher = teacher; }
    public ProfessionType teacher() { return teacher; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static Optional<Technique> taughtBy(ProfessionType profession) {
        for (var technique : values()) if (technique.teacher == profession) return Optional.of(technique);
        return Optional.empty();
    }
    public static Technique fromId(String id) { return valueOf(id.toUpperCase(java.util.Locale.ROOT)); }
}

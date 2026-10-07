package dev.livingkingdoms.faction;

import java.util.Objects;

/** Baseline relationship policy. Independent hostile factions have no assumed mutual alliance. */
public final class FactionRelations {
    private FactionRelations() {}

    public static FactionRelation between(Faction first, Faction second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first == second) return FactionRelation.ALLY;
        if ((first.isAllied() && isInitialHostile(second))
                || (second.isAllied() && isInitialHostile(first))) return FactionRelation.HOSTILE;
        // A future policy can decide e.g. Pillagers versus Undead without changing domain records.
        return FactionRelation.NEUTRAL;
    }

    private static boolean isInitialHostile(Faction faction) {
        return faction == Faction.PILLAGER || faction == Faction.BANDIT || faction == Faction.UNDEAD;
    }
}

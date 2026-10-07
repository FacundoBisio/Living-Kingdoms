package dev.livingkingdoms.faction;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Central symmetric relationship policy; unspecified pairs remain neutral. */
public final class FactionRelations {
    private static final Map<Faction, Map<Faction, FactionRelation>> RELATIONS = initialRelations();

    private FactionRelations() {}

    public static FactionRelation between(Faction first, Faction second) {
        return getRelation(first, second);
    }

    public static FactionRelation getRelation(Faction first, Faction second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first == second) return FactionRelation.ALLY;
        return RELATIONS.getOrDefault(first, Map.of()).getOrDefault(second, FactionRelation.NEUTRAL);
    }

    public static boolean isHostile(Faction first, Faction second) {
        return getRelation(first, second) == FactionRelation.HOSTILE;
    }

    private static Map<Faction, Map<Faction, FactionRelation>> initialRelations() {
        Map<Faction, Map<Faction, FactionRelation>> relations = new EnumMap<>(Faction.class);
        hostile(relations, Faction.ALLIED_KINGDOM, Faction.PILLAGER);
        hostile(relations, Faction.ALLIED_KINGDOM, Faction.BANDIT);
        hostile(relations, Faction.ALLIED_KINGDOM, Faction.UNDEAD);
        hostile(relations, Faction.PILLAGER, Faction.UNDEAD);
        hostile(relations, Faction.BANDIT, Faction.UNDEAD);
        relations.replaceAll((faction, row) -> Map.copyOf(row));
        return Map.copyOf(relations);
    }

    private static void hostile(Map<Faction, Map<Faction, FactionRelation>> relations,
                                Faction first, Faction second) {
        relations.computeIfAbsent(first, ignored -> new EnumMap<>(Faction.class)).put(second, FactionRelation.HOSTILE);
        relations.computeIfAbsent(second, ignored -> new EnumMap<>(Faction.class)).put(first, FactionRelation.HOSTILE);
    }
}

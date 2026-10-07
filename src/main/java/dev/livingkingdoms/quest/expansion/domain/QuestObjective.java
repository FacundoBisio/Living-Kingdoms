package dev.livingkingdoms.quest.expansion.domain;

import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Objective values contain identifiers and terms, never entity references or loaded chunks. */
public sealed interface QuestObjective permits QuestObjective.Resource, QuestObjective.Party,
        QuestObjective.Meet, QuestObjective.Return {
    record Resource(List<ResourceRequirement> requirements) implements QuestObjective {
        public Resource {
            requirements = List.copyOf(requirements);
            if (requirements.isEmpty() || requirements.size() > ResourceKind.values().length)
                throw new IllegalArgumentException("Resource objective requires 1..4 distinct resources");
            EnumSet<ResourceKind> kinds = EnumSet.noneOf(ResourceKind.class);
            for (ResourceRequirement requirement : requirements)
                if (!kinds.add(requirement.resource())) throw new IllegalArgumentException("Repeated quest resource");
        }
    }
    record Party(UUID partyId, Faction faction, PartyType partyType) implements QuestObjective {
        public Party {
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(faction, "faction");
            Objects.requireNonNull(partyType, "partyType");
            if (faction.isAllied() || partyType.faction() != faction)
                throw new IllegalArgumentException("Target party faction must match its hostile type");
        }
    }
    record Meet(QuestSourceRole role) implements QuestObjective {
        public Meet { Objects.requireNonNull(role, "role"); }
    }
    record Return() implements QuestObjective {}
}

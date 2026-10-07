package dev.livingkingdoms.quest.expansion.persistence;

import dev.livingkingdoms.encounter.domain.PartyType;
import dev.livingkingdoms.faction.Faction;
import dev.livingkingdoms.progression.domain.LevelValue;
import dev.livingkingdoms.quest.domain.QuestState;
import dev.livingkingdoms.quest.expansion.domain.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Strict additive schema boundary for the expanded quest records in the existing quest store. */
public final class ExpandedQuestNbt {
    private ExpandedQuestNbt() {}

    public static CompoundTag write(QuestInstance quest) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", quest.id());
        tag.putString("template", quest.template().id());
        CompoundTag source = new CompoundTag();
        source.putUUID("settlement", quest.source().settlementId());
        if (quest.source().npcId() != null) source.putUUID("npc", quest.source().npcId());
        source.putString("role", quest.source().role().name());
        tag.put("source", source);
        CompoundTag objective = new CompoundTag();
        if (quest.objective() instanceof QuestObjective.Resource resources) {
            objective.putString("kind", "resource");
            ListTag entries = new ListTag();
            for (ResourceRequirement requirement : resources.requirements()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("resource", requirement.resource().id());
                entry.putInt("count", requirement.count());
                entries.add(entry);
            }
            objective.put("requirements", entries);
        } else if (quest.objective() instanceof QuestObjective.Party party) {
            objective.putString("kind", "party");
            objective.putUUID("party", party.partyId());
            objective.putString("faction", party.faction().id());
            objective.putString("party_type", party.partyType().id());
        } else if (quest.objective() instanceof QuestObjective.Meet meet) {
            objective.putString("kind", "meet");
            objective.putString("role", meet.role().name());
        } else if (quest.objective() instanceof QuestObjective.Return) {
            objective.putString("kind", "return");
        } else throw new IllegalArgumentException("Unknown quest objective");
        tag.put("objective", objective);
        tag.putInt("recommended_level", quest.recommendedLevel().value());
        tag.putString("difficulty", quest.difficulty().name());
        CompoundTag rewards = new CompoundTag();
        rewards.putInt("emeralds", quest.rewards().emeralds());
        rewards.putInt("reputation", quest.rewards().reputation());
        tag.put("rewards", rewards);
        tag.putString("state", quest.state().name());
        tag.putBoolean("objective_satisfied", quest.objectiveSatisfied());
        tag.putLong("created_at", quest.createdAt());
        tag.putLong("expires_at", quest.expiresAt());
        return tag;
    }

    public static QuestInstance read(CompoundTag tag) {
        UUID id = uuid(tag, "id");
        require(tag, "template", Tag.TAG_STRING);
        QuestTemplate template = QuestTemplate.fromId(tag.getString("template"));
        require(tag, "source", Tag.TAG_COMPOUND);
        CompoundTag source = tag.getCompound("source");
        require(source, "role", Tag.TAG_STRING);
        UUID npc = source.contains("npc") ? uuid(source, "npc") : null;
        QuestSource questSource = new QuestSource(uuid(source, "settlement"), npc,
                QuestSourceRole.valueOf(source.getString("role")));
        require(tag, "objective", Tag.TAG_COMPOUND);
        CompoundTag objective = tag.getCompound("objective");
        require(objective, "kind", Tag.TAG_STRING);
        QuestObjective questObjective = switch (objective.getString("kind")) {
            case "resource" -> {
                ListTag entries = compoundList(objective, "requirements");
                if (entries.isEmpty() || entries.size() > 16) throw new IllegalArgumentException("Invalid resource objective size");
                List<ResourceRequirement> requirements = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    CompoundTag entry = entries.getCompound(i);
                    require(entry, "resource", Tag.TAG_STRING);
                    require(entry, "count", Tag.TAG_INT);
                    requirements.add(new ResourceRequirement(ResourceKind.fromId(entry.getString("resource")), entry.getInt("count")));
                }
                yield new QuestObjective.Resource(requirements);
            }
            case "party" -> {
                require(objective, "faction", Tag.TAG_STRING);
                require(objective, "party_type", Tag.TAG_STRING);
                yield new QuestObjective.Party(uuid(objective, "party"), Faction.fromId(objective.getString("faction")),
                        PartyType.fromId(objective.getString("party_type")));
            }
            case "meet" -> {
                require(objective, "role", Tag.TAG_STRING);
                yield new QuestObjective.Meet(QuestSourceRole.valueOf(objective.getString("role")));
            }
            case "return" -> new QuestObjective.Return();
            default -> throw new IllegalArgumentException("Unknown quest objective kind");
        };
        require(tag, "recommended_level", Tag.TAG_INT);
        require(tag, "difficulty", Tag.TAG_STRING);
        require(tag, "rewards", Tag.TAG_COMPOUND);
        CompoundTag rewards = tag.getCompound("rewards");
        require(rewards, "emeralds", Tag.TAG_INT);
        require(rewards, "reputation", Tag.TAG_INT);
        require(tag, "state", Tag.TAG_STRING);
        require(tag, "objective_satisfied", Tag.TAG_BYTE);
        if (tag.getByte("objective_satisfied") < 0 || tag.getByte("objective_satisfied") > 1) {
            throw new IllegalArgumentException("Invalid objective flag");
        }
        require(tag, "created_at", Tag.TAG_LONG);
        require(tag, "expires_at", Tag.TAG_LONG);
        return new QuestInstance(id, template, questSource, questObjective,
                new LevelValue(tag.getInt("recommended_level")), QuestDifficulty.valueOf(tag.getString("difficulty")),
                new QuestRewards(rewards.getInt("emeralds"), rewards.getInt("reputation")),
                QuestState.valueOf(tag.getString("state")), tag.getBoolean("objective_satisfied"),
                tag.getLong("created_at"), tag.getLong("expires_at"));
    }

    public static UUID uuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) throw new IllegalArgumentException("Missing or invalid expanded quest UUID: " + field);
        return tag.getUUID(field);
    }

    public static ListTag compoundList(CompoundTag tag, String field) {
        require(tag, field, Tag.TAG_LIST);
        ListTag entries = (ListTag) tag.get(field);
        if (!entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Expanded quest field must contain compounds: " + field);
        }
        return entries;
    }

    public static void require(CompoundTag tag, String field, int type) {
        if (!tag.contains(field, type)) throw new IllegalArgumentException("Missing or invalid expanded quest field: " + field);
    }
}

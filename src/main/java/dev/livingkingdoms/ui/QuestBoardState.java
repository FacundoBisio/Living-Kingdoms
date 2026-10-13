package dev.livingkingdoms.ui;

import net.minecraft.nbt.CompoundTag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure client selection policy shared by rendering and packet transitions. */
public final class QuestBoardState {
    private QuestBoardState() {}

    public static List<CompoundTag> visible(CompoundTag data, int section) {
        List<CompoundTag> result = new ArrayList<>();
        var list = data.getList("quests", 10);
        for (int i = 0; i < list.size(); i++) {
            var quest = list.getCompound(i);
            boolean active = quest.getString("state").equals("ACTIVE");
            if (section == 0 && quest.getString("category").equals("MAIN")
                    || section == 1 && quest.getString("category").equals("DYNAMIC") && !active
                    || section == 2 && active) result.add(quest);
        }
        return result;
    }

    /** Current work plus one upcoming prerequisite; history is an explicit reader preference. */
    public static List<CompoundTag> visible(CompoundTag data, int section, boolean history) {
        var all = visible(data, section);
        if (history || section == 2) return all;
        var current = new ArrayList<CompoundTag>();
        CompoundTag nextLocked = null, lastCompleted = null;
        for (var quest : all) {
            switch (quest.getString("state")) {
                case "ACTIVE", "AVAILABLE" -> current.add(quest);
                case "LOCKED" -> { if (nextLocked == null) nextLocked = quest; }
                case "COMPLETED" -> lastCompleted = quest;
                default -> { }
            }
        }
        if (section == 0 && nextLocked != null) current.add(nextLocked);
        if (section == 0 && current.isEmpty() && lastCompleted != null) current.add(lastCompleted);
        return List.copyOf(current);
    }

    public static int afterAction(CompoundTag data, UUID selected, UiPayloads.Action action, int section) {
        if (selected == null || data.contains("notice")) return section;
        var list = data.getList("quests", 10);
        for (int i = 0; i < list.size(); i++) {
            var quest = list.getCompound(i);
            if (!selected.equals(quest.getUUID("id"))) continue;
            if (action == UiPayloads.Action.ACCEPT && quest.getString("state").equals("ACTIVE")) return 2;
            if (action == UiPayloads.Action.CLAIM && quest.getString("state").equals("COMPLETED"))
                return quest.getString("category").equals("MAIN") ? 0 : 1;
        }
        return section;
    }
}

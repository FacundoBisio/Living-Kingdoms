package dev.livingkingdoms.rpg;

import dev.livingkingdoms.construction.domain.ConstructionState;
import dev.livingkingdoms.construction.persistence.ConstructionSavedData;
import dev.livingkingdoms.quest.expansion.domain.QuestInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import java.util.Comparator;

/**
 * Read-only, derived checklists that turn vague main objectives into concrete next steps. Nothing is
 * persisted: every step is recomputed from construction receipts when the board snapshot is built.
 */
public final class QuestGuides {
    private QuestGuides() {}

    public static ListTag development(MinecraftServer server, QuestInstance quest) {
        var projects = ConstructionSavedData.get(server).projects(quest.source().settlementId());
        var project = projects.stream()
                .filter(e -> e.project().state() == ConstructionState.COMPLETED && e.project().completedAt() >= quest.createdAt())
                .findFirst()
                .or(() -> projects.stream().filter(e -> e.project().state() != ConstructionState.COMPLETED)
                        .min(Comparator.comparingLong(e -> e.project().createdAt())))
                .map(ConstructionSavedData.Entry::project).orElse(null);
        boolean planned = project != null;
        boolean funded = planned && project.funded();
        boolean builder = planned && (!project.builderRequired() || project.builderId() != null || project.state() == ConstructionState.COMPLETED);
        boolean done = planned && project.state() == ConstructionState.COMPLETED;
        var steps = new ListTag();
        steps.add(step("plan", planned));
        steps.add(step("supply", funded));
        if (!planned || project.builderRequired()) steps.add(step("builder", builder));
        steps.add(step("wait", done));
        steps.add(step("return", false));
        return steps;
    }

    private static CompoundTag step(String key, boolean done) {
        var tag = new CompoundTag();
        tag.putString("key", "quest.livingkingdoms.step.development." + key);
        tag.putBoolean("done", done);
        return tag;
    }
}

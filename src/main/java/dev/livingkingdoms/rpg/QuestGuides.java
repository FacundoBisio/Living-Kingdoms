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
        return steps(project, true);
    }

    /** Next-step kinds shared by the HUD site row; derived from the same state as the checklist. */
    public enum Next { REASON, NEEDS, BUILDER, BUILDING, NONE }

    public static Next next(boolean blocked, boolean funded, boolean builderRequired, boolean hasBuilder, boolean building) {
        if (blocked) return Next.REASON;
        if (!funded) return Next.NEEDS;
        if (builderRequired && !hasBuilder) return Next.BUILDER;
        return building ? Next.BUILDING : Next.NONE;
    }

    /** Plan, supply, builder, wait (+ the quest-only "return" step); a null project means nothing is planned yet. */
    public static ListTag steps(dev.livingkingdoms.construction.domain.ConstructionProject project, boolean withReturn) {
        boolean planned = project != null;
        boolean funded = planned && project.funded();
        boolean builder = planned && (!project.builderRequired() || project.builderId() != null || project.state() == ConstructionState.COMPLETED);
        boolean done = planned && project.state() == ConstructionState.COMPLETED;
        var steps = new ListTag();
        steps.add(step("plan", planned));
        steps.add(step("supply", funded));
        if (!planned || project.builderRequired()) steps.add(step("builder", builder));
        steps.add(step("wait", done));
        if (withReturn) steps.add(step("return", false));
        return steps;
    }

    private static CompoundTag step(String key, boolean done) {
        var tag = new CompoundTag();
        tag.putString("key", "quest.livingkingdoms.step.development." + key);
        tag.putBoolean("done", done);
        return tag;
    }
}

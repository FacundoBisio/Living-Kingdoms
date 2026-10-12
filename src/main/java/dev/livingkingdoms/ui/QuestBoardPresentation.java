package dev.livingkingdoms.ui;

import net.minecraft.nbt.CompoundTag;

/** Bounded snapshot-only presentation; never infers acceptance or reward authority. */
public final class QuestBoardPresentation {
    private QuestBoardPresentation() {}

    public enum Status {
        LOCKED("x"), AVAILABLE("+"), ACTIVE(">"), COMPLETED("v"), FAILED("!");
        private final String mark;
        Status(String mark) { this.mark = mark; }
        public String mark() { return mark; }
    }

    public static Status status(CompoundTag quest) {
        return switch (quest.getString("state")) {
            case "LOCKED" -> Status.LOCKED;
            case "AVAILABLE" -> Status.AVAILABLE;
            case "ACTIVE" -> Status.ACTIVE;
            case "COMPLETED" -> Status.COMPLETED;
            default -> Status.FAILED;
        };
    }

    public record Progress(int current, int total) {
        public Progress {
            if (total < 1 || current < 0 || current > total) throw new IllegalArgumentException("Invalid displayed progress");
        }
        public double fraction() { return (double) current / total; }
    }

    public static Progress progress(CompoundTag quest) {
        if (status(quest) == Status.LOCKED) return null;
        var items = quest.getList("requirements", 10);
        int total = 0, current = 0;
        // Domain resource objectives have at most four terms. This also bounds malformed snapshots.
        for (int i = 0; i < Math.min(4, items.size()); i++) {
            var item = items.getCompound(i);
            int required = Math.clamp(item.getInt("required"), 0, 100_000);
            total += required;
            current += Math.clamp(item.getInt("count"), 0, required);
        }
        if (total > 0) return new Progress(current, total);
        int defenders = Math.clamp(quest.getInt("defense_total"), 0, 100_000);
        if (defenders > 0) return new Progress(defenders - Math.clamp(quest.getInt("defense_remaining"), 0, defenders), defenders);
        return null;
    }
}

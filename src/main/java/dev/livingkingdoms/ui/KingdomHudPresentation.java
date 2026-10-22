package dev.livingkingdoms.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;

/** Snapshot-only descriptions shared by client caching and tests. No world/entity lookups. */
public final class KingdomHudPresentation {
    public enum Icon { KINGDOM, REPUTATION, PEOPLE, SHIELD, QUEST, PROGRESS, CHECK, LOCATION }
    public enum Tone { TITLE, NORMAL, MUTED, SUCCESS, WARNING }
    public record Row(Icon icon, Component text, Tone tone) {}
    public record Panels(List<Row> kingdom, List<Row> quest) {
        public Panels { kingdom = List.copyOf(kingdom); quest = List.copyOf(quest); }
    }
    private KingdomHudPresentation() {}

    public static Panels describe(CompoundTag data, boolean expanded, boolean showKingdom, boolean showQuest) {
        var kingdom = new ArrayList<Row>(); var quest = new ArrayList<Row>();
        if (showKingdom && data.contains("settlement", 10)) {
            var local = data.getCompound("settlement");
            kingdom.add(new Row(Icon.KINGDOM, Component.literal(local.getString("name")).withStyle(ChatFormatting.BOLD), Tone.TITLE));
            kingdom.add(new Row(Icon.REPUTATION, tr("reputation_value", local.getInt("reputation")), Tone.NORMAL));
            kingdom.add(new Row(Icon.PEOPLE, expanded ? tr("population", local.getInt("population"), local.getInt("housing")) : tr("population_value", local.getInt("population")), Tone.NORMAL));
            if (local.getString("safety").equals("threatened")) kingdom.add(new Row(Icon.SHIELD, tr("threatened"), Tone.WARNING));
            else if (expanded) kingdom.add(new Row(Icon.SHIELD, tr("safe"), Tone.MUTED));
            if (expanded) kingdom.add(new Row(Icon.REPUTATION, tr("reputation", local.getInt("level"), local.getInt("progress"), local.getInt("next")), Tone.MUTED));
        }
        if (showQuest && data.contains("quest", 10)) {
            var tracked = data.getCompound("quest"); String template = tracked.getString("template");
            boolean complete = tracked.getString("state").equals("COMPLETED"), ready = tracked.getBoolean("ready");
            Component title = template.equals("local_defense") ? Component.translatable("ui.livingkingdoms.defense.title", tracked.getString("settlement"))
                    : Component.translatable("quest.livingkingdoms." + template + ".title");
            quest.add(new Row(Icon.QUEST, title.copy().withStyle(ChatFormatting.BOLD), Tone.TITLE));
            quest.add(new Row(Icon.PROGRESS, objective(tracked), Tone.NORMAL));
            var requirements = tracked.getList("requirements", 10);
            for (int i = 0; i < Math.min(4, requirements.size()); i++) {
                var item = requirements.getCompound(i); int total = Math.clamp(item.getInt("required"), 1, 100_000);
                var description = switch (item.getString("item")) { case "iron_ingot" -> Items.IRON_INGOT; case "wheat" -> Items.WHEAT;
                    case "logs" -> Items.OAK_LOG; default -> Items.STONE; };
                quest.add(new Row(Icon.PROGRESS, tr("resource", Math.clamp(item.getInt("count"), 0, total), total, description.getDescription()), Tone.NORMAL));
            }
            int total = tracked.getBoolean("defense") ? tracked.getInt("defense_total") : tracked.getInt("party_total");
            if (total > 0) {
                total = Math.min(100_000, total);
                int remaining = Math.clamp(tracked.getInt(tracked.getBoolean("defense") ? "defense_remaining" : "party_remaining"), 0, total);
                quest.add(new Row(Icon.PROGRESS, tr("defeated", total - remaining, total), Tone.NORMAL));
            } else if (tracked.contains("party_remaining")) quest.add(new Row(Icon.PROGRESS, tr("remaining", tracked.getInt("party_remaining")), Tone.NORMAL));
            else if (requirements.isEmpty() && !ready && !complete) quest.add(new Row(Icon.PROGRESS, tr("step_progress", 0, 1), Tone.MUTED));
            if (complete || ready) quest.add(new Row(Icon.CHECK, tr(complete ? "completed" : "ready"), Tone.SUCCESS));
            if ((expanded || ready) && !tracked.getString("settlement").isBlank())
                quest.add(new Row(Icon.LOCATION, Component.translatable("ui.livingkingdoms.claim_at", tracked.getString("settlement")), Tone.MUTED));
            if (expanded && tracked.contains("target_x")) quest.add(new Row(Icon.LOCATION,
                    Component.translatable("quest.livingkingdoms.party_location", tracked.getInt("target_x"), tracked.getInt("target_z")), Tone.MUTED));
        }
        return new Panels(kingdom, quest);
    }
    private static Component objective(CompoundTag quest) {
        String key = switch (quest.getString("template")) {
            case "first_meeting" -> "meet_mayor"; case "main_residents" -> "resident";
            case "main_development" -> "construction"; case "main_watchtower" -> "tower";
            case "main_return" -> "return"; case "main_patrol", "pillager_request" -> "patrol";
            case "undead_request" -> "undead";
            case "bandit_request" -> "bandit";
            case "warband_request" -> "warband";
            case "local_defense" -> quest.getString("defense_state").equals("approaching") || quest.getString("defense_state").equals("detected") ? "approach" : "defense";
            default -> quest.getList("requirements", 10).isEmpty() ? "task" : "delivery";
        };
        return tr("objective." + key);
    }
    private static Component tr(String key, Object... args) { return Component.translatable("hud.livingkingdoms." + key, args); }
}

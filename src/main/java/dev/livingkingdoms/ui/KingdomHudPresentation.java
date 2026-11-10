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
            if (local.getBoolean("hostile")) {
                kingdom.add(new Row(Icon.SHIELD, tr("hostile_kingdom", local.getString("ruler")), Tone.WARNING));
                if (expanded) kingdom.add(new Row(Icon.SHIELD, tr("war_strength", Math.clamp(local.getInt("war_strength"), 1, 10)), Tone.MUTED));
                renown(kingdom, local, expanded);
            } else {
                if (local.contains("rank", 3)) kingdom.add(new Row(Icon.PROGRESS, tr("kingdom_rank", Math.clamp(local.getInt("rank"), 1, 20),
                        Math.max(0, local.getLong("rank_progress")), Math.max(1, local.getLong("rank_span"))), Tone.SUCCESS));
                if (local.contains("event", 10)) {
                    var event = local.getCompound("event"); String kind = event.getString("kind");
                    if (kind.matches("[a-z_]{1,24}")) {
                        long sec = Math.max(0, event.getLong("seconds"));
                        var time = String.format("%d:%02d", sec / 60, sec % 60);
                        kingdom.add(new Row(Icon.QUEST, tr(event.getString("phase").equals("active") ? "event_active" : "event_upcoming",
                                Component.translatable("event.livingkingdoms." + kind), time), Tone.WARNING));
                        if (event.contains("distance")) kingdom.add(new Row(Icon.LOCATION, tr("treasure", event.getInt("distance"),
                                Component.translatable("hud.livingkingdoms.dir." + event.getString("direction"))), Tone.NORMAL));
                    }
                }
                if (local.contains("site", 10)) {
                    var site = local.getCompound("site");
                    kingdom.add(new Row(Icon.PROGRESS, tr(site.getBoolean("blocked") ? "site_blocked" : "site",
                            Component.translatable("construction.livingkingdoms.building." + site.getString("building")),
                            Math.clamp(site.getInt("percent"), 0, 100), site.getInt("helpers")), site.getBoolean("blocked") ? Tone.WARNING : Tone.NORMAL));
                }
                if (local.getInt("contracts_ready") > 0) kingdom.add(new Row(Icon.CHECK, tr("contracts_ready", local.getInt("contracts_ready")), Tone.SUCCESS));
                if (local.contains("wall_building")) kingdom.add(new Row(Icon.SHIELD, tr("wall_building"), Tone.MUTED));
                if (local.getInt("expeditions") > 0) {
                    long next = Math.max(0, local.getLong("expedition_next"));
                    kingdom.add(new Row(Icon.LOCATION, tr("expeditions", local.getInt("expeditions"), String.format(java.util.Locale.ROOT, "%d:%02d", next / 60, next % 60)), Tone.NORMAL));
                }
                if (local.getInt("wall_breaches") > 0) kingdom.add(new Row(Icon.SHIELD, tr("wall_breaches", local.getInt("wall_breaches")), Tone.WARNING));
                if (local.getInt("alarm") > 0) kingdom.add(new Row(Icon.SHIELD, tr("alarm", local.getInt("alarm") / 60, String.format(java.util.Locale.ROOT, "%02d", local.getInt("alarm") % 60)), Tone.WARNING));
                kingdom.add(new Row(Icon.REPUTATION, tr("reputation_value", local.getInt("reputation")), Tone.NORMAL));
                kingdom.add(new Row(Icon.PEOPLE, expanded ? tr("population", local.getInt("population"), local.getInt("housing")) : tr("population_value", local.getInt("population")), Tone.NORMAL));
                if (local.getString("safety").equals("threatened")) kingdom.add(new Row(Icon.SHIELD, tr("threatened"), Tone.WARNING));
                else if (expanded) kingdom.add(new Row(Icon.SHIELD, tr("safe"), Tone.MUTED));
                if (expanded) kingdom.add(new Row(Icon.REPUTATION, tr("reputation", local.getInt("level"), local.getInt("progress"), local.getInt("next")), Tone.MUTED));
                renown(kingdom, local, expanded);
            }
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
    private static void renown(List<Row> rows, CompoundTag local, boolean expanded) {
        if (!expanded || !local.contains("renown_rank", 8)) return;
        String rank = local.getString("renown_rank");
        // Unknown ids from a newer server fall back to the raw id rather than failing the HUD.
        Component title = rank.matches("[a-z_]{1,32}") ? Component.translatable("renown.livingkingdoms.rank." + rank) : Component.literal("?");
        rows.add(new Row(Icon.REPUTATION, tr("renown", title, Math.max(0, local.getInt("renown"))), Tone.MUTED));
    }
    private static Component tr(String key, Object... args) { return Component.translatable("hud.livingkingdoms." + key, args); }
}

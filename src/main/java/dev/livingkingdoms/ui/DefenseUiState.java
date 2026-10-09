package dev.livingkingdoms.ui;

import dev.livingkingdoms.defense.domain.SettlementThreatEvent;
import dev.livingkingdoms.defense.domain.ThreatState;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Read-only presentation of the shared event and this player's individual outcome. */
public final class DefenseUiState {
    private DefenseUiState() {}

    public static void write(CompoundTag tag, SettlementThreatEvent event, UUID player) {
        tag.putBoolean("defense",true);
        tag.putString("defense_faction",event.faction().id());
        tag.putInt("defense_threat",event.threatRating());
        tag.putInt("defense_remaining",event.remainingMembers());
        tag.putInt("defense_total",event.totalMembers());
        tag.putString("defense_state",event.state().name().toLowerCase(java.util.Locale.ROOT));
        tag.putString("defense_outcome",event.outcome().name().toLowerCase(java.util.Locale.ROOT));
        tag.putBoolean("defense_participated",event.successfulParticipant(player));
        tag.putBoolean("defense_debug",event.debug());
        tag.putBoolean("defense_reward_eligible",event.rewardEligible());
        tag.putString("defense_note",note(event,player));
    }

    public static String note(SettlementThreatEvent event, UUID player) {
        if(event.debug() && !event.rewardEligible()) return "ui.livingkingdoms.defense.practice";
        if(event.state()==ThreatState.FAILED) return "ui.livingkingdoms.defense.failed";
        if(event.state()==ThreatState.RESOLVED)
            return event.successfulParticipant(player)?"ui.livingkingdoms.defense.participated":"ui.livingkingdoms.defense.no_credit";
        return "ui.livingkingdoms.defense.participation";
    }

    public static String dialogue(SettlementThreatEvent event, UUID player) {
        if(event.threatened()) return "ui.livingkingdoms.dialogue.threatened";
        if(event.state()==ThreatState.FAILED) return "ui.livingkingdoms.dialogue.defense_failed";
        if(event.debug() && !event.rewardEligible()) return "ui.livingkingdoms.dialogue.defense_practice";
        if(event.successfulParticipant(player)) return "ui.livingkingdoms.dialogue.defense_helped";
        return event.participants().isEmpty()?"ui.livingkingdoms.dialogue.defense_guards":"ui.livingkingdoms.dialogue.defense_others";
    }
}

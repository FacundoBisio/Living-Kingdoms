package dev.livingkingdoms.ui;

import dev.livingkingdoms.defense.domain.*;
import dev.livingkingdoms.faction.Faction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DefenseUiStateTest {
    @Test void activeDefensePresentationUsesPartyProgressAndKeepsTechnicalIdsPrivate() {
        var event=active(false,true).withProgress(2); var snapshot=new CompoundTag();
        DefenseUiState.write(snapshot,event,UUID.randomUUID());
        assertEquals("pillager",snapshot.getString("defense_faction"));
        assertEquals(2,snapshot.getInt("defense_remaining"));
        assertEquals(4,snapshot.getInt("defense_total"));
        assertEquals("active",snapshot.getString("defense_state"));
        assertFalse(snapshot.getBoolean("defense_participated"));
        assertFalse(snapshot.contains("id")); assertFalse(snapshot.contains("party"));
        assertEquals("ui.livingkingdoms.dialogue.threatened",DefenseUiState.dialogue(event,UUID.randomUUID()));
    }

    @Test void oneSharedVictoryPresentsCreditIndividuallyToEachPlayer() {
        UUID participant=UUID.randomUUID(),other=UUID.randomUUID();
        var event=active(false,true).withProgress(0).resolved(20,Set.of(participant));
        var credited=new CompoundTag(); var uncredited=new CompoundTag();
        DefenseUiState.write(credited,event,participant); DefenseUiState.write(uncredited,event,other);
        assertTrue(credited.getBoolean("defense_participated"));
        assertFalse(uncredited.getBoolean("defense_participated"));
        assertEquals("ui.livingkingdoms.defense.participated",credited.getString("defense_note"));
        assertEquals("ui.livingkingdoms.defense.no_credit",uncredited.getString("defense_note"));
        assertEquals("ui.livingkingdoms.dialogue.defense_helped",DefenseUiState.dialogue(event,participant));
        assertEquals("ui.livingkingdoms.dialogue.defense_others",DefenseUiState.dialogue(event,other));
    }

    @Test void guardVictoryAndExpiredDefenseHaveDistinctPlayerFacingOutcomes() {
        UUID player=UUID.randomUUID();
        var victory=active(false,true).withProgress(0).resolved(20,Set.of());
        var failure=active(false,true).failed(100,ThreatOutcome.TIMEOUT);
        assertEquals("ui.livingkingdoms.dialogue.defense_guards",DefenseUiState.dialogue(victory,player));
        assertEquals("ui.livingkingdoms.defense.no_credit",DefenseUiState.note(victory,player));
        assertEquals("ui.livingkingdoms.dialogue.defense_failed",DefenseUiState.dialogue(failure,player));
        assertEquals("ui.livingkingdoms.defense.failed",DefenseUiState.note(failure,player));
        assertFalse(victory.threatened()); assertFalse(failure.threatened());
    }

    @Test void debugPracticeNeverImpliesPersonalReward() {
        var event=active(true,false).withProgress(0).resolved(20,Set.of());
        UUID player=UUID.randomUUID();
        assertEquals("ui.livingkingdoms.defense.practice",DefenseUiState.note(event,player));
        assertEquals("ui.livingkingdoms.dialogue.defense_practice",DefenseUiState.dialogue(event,player));
        var snapshot=new CompoundTag(); DefenseUiState.write(snapshot,event,player);
        assertTrue(snapshot.getBoolean("defense_debug")); assertFalse(snapshot.getBoolean("defense_participated"));
        assertFalse(snapshot.getBoolean("defense_reward_eligible"));
    }

    @Test void explicitlyRewardEnabledDevelopmentEncounterPreservesIndividualCredit() {
        UUID player=UUID.randomUUID();
        var event=active(true,true).withProgress(0).resolved(20,Set.of(player));
        var snapshot=new CompoundTag(); DefenseUiState.write(snapshot,event,player);
        assertTrue(snapshot.getBoolean("defense_debug")); assertTrue(snapshot.getBoolean("defense_reward_eligible"));
        assertTrue(snapshot.getBoolean("defense_participated"));
        assertEquals("ui.livingkingdoms.defense.participated",DefenseUiState.note(event,player));
        assertEquals("ui.livingkingdoms.dialogue.defense_helped",DefenseUiState.dialogue(event,player));
    }

    private static SettlementThreatEvent active(boolean debug,boolean rewards) {
        return new SettlementThreatEvent(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",Faction.PILLAGER,
                UUID.randomUUID(),15,2,4,4,ThreatState.DETECTED,ThreatOutcome.NONE,0,-1,-1,100,debug,rewards,Set.of()).activated(1);
    }
}

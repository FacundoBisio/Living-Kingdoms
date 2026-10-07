package dev.livingkingdoms.encounter.persistence;

import dev.livingkingdoms.encounter.domain.*;
import dev.livingkingdoms.progression.domain.LevelSummary;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PartyProgressionPersistenceTest {
    private static HostileParty party() {
        Set<UUID> members = Set.of(UUID.randomUUID(), UUID.randomUUID());
        return new HostileParty(UUID.randomUUID(), PartyType.PILLAGER_PATROL.faction(), PartyType.PILLAGER_PATROL,
                new OriginRegion("minecraft:overworld", 1000, 64, 1000, 8), null, PartyState.ALIVE, members, members, 2, 2,
                false, true, new LevelSummary(2, 20, 8, 12));
    }
    @Test void summarySurvivesReloadDeathAndMemberConversion() {
        var party = party(); var data = new EncounterSavedData(); data.add(party, 100);
        UUID member = party.memberIds().iterator().next(); assertTrue(data.replaceMember(member, UUID.randomUUID()));
        var changed = data.get(party.id()).orElseThrow(); data.recordDeath(changed.memberIds().iterator().next(), 200);
        var restored = EncounterSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(party.levels(), restored.get(party.id()).orElseThrow().levels());
    }
    @Test void legacySchemaOneAndTwoMissingLevelsDefaultWithoutLosingState() {
        var party = party(); var data = new EncounterSavedData(); data.add(party, 100);
        CompoundTag tag = data.save(new CompoundTag(), null);
        tag.getList("parties", Tag.TAG_COMPOUND).getCompound(0).remove("levels");
        for (int version : new int[]{1, 2}) {
            tag.putInt("schema_version", version);
            var restored = EncounterSavedData.load(tag, null).get(party.id()).orElseThrow();
            assertEquals(LevelSummary.uniform(2, 1), restored.levels()); assertEquals(party.remainingMembers(), restored.remainingMembers());
            assertEquals(party.rewardEligible(), restored.rewardEligible()); assertEquals(party.threatRating(), restored.threatRating());
        }
    }
    @Test void malformedSummaryAndRosterCountMismatchAreRejected() {
        var data = new EncounterSavedData(); data.add(party()); CompoundTag valid = data.save(new CompoundTag(), null);
        CompoundTag bad = valid.copy(); bad.getList("parties", Tag.TAG_COMPOUND).getCompound(0).getCompound("levels").putInt("count", 3);
        assertThrows(IllegalArgumentException.class, () -> EncounterSavedData.load(bad, null));
        CompoundTag malformed = valid.copy(); malformed.getList("parties", Tag.TAG_COMPOUND).getCompound(0).putString("levels", "invalid");
        assertThrows(IllegalArgumentException.class, () -> EncounterSavedData.load(malformed, null));
    }
}

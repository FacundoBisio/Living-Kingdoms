package dev.livingkingdoms.onboarding;

import dev.livingkingdoms.onboarding.domain.KingdomName;
import dev.livingkingdoms.onboarding.domain.OnboardingStage;
import dev.livingkingdoms.onboarding.persistence.KingdomOnboardingSavedData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KingdomOnboardingTest {
    private final UUID player = UUID.randomUUID(), settlement = UUID.randomUUID();

    private void introduction(KingdomOnboardingSavedData data, UUID owner) {
        assertTrue(data.begin(owner, settlement));
        assertFalse(data.advance(owner, settlement, "REGISTER"));
        assertTrue(data.advance(owner, settlement, "NEXT"));
        assertTrue(data.advance(owner, settlement, "NEXT"));
        assertFalse(data.advance(owner, settlement, "NEXT"));
        assertTrue(data.advance(owner, settlement, "HELP"));
        assertTrue(data.advance(owner, settlement, "ACCEPT"));
        assertEquals(OnboardingStage.NAMING, data.stage(owner, settlement));
    }

    @Test void namesArePlainBoundedAndNormalized() {
        assertEquals("Reino del Río", KingdomName.validate("  Reino  del Río  ").orElseThrow());
        assertEquals("King's Landing", KingdomName.validate("King's Landing").orElseThrow());
        for (String invalid : new String[] {"", "   ", "-", "\nOak", "Oak\tHaven", "§cOak", "Oak\u200bHaven", "x".repeat(33), "<Kingdom>"})
            assertTrue(KingdomName.validate(invalid).isEmpty(), invalid);
    }

    @Test void mayorIntroductionCannotBeSkippedOrRepeatedAndPersists() {
        var data = new KingdomOnboardingSavedData();
        assertFalse(data.join(player, settlement, "Oakhaven"));
        introduction(data, player);
        var resumed = KingdomOnboardingSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(OnboardingStage.NAMING, resumed.stage(player, settlement));
        assertFalse(resumed.join(player, settlement, "§aOak"));
        assertTrue(resumed.join(player, settlement, "Oakhaven"));
        assertFalse(resumed.join(player, settlement, "Another"));
        assertFalse(resumed.begin(player, settlement));
        var reloaded = KingdomOnboardingSavedData.load(resumed.save(new CompoundTag(), null), null);
        assertEquals(OnboardingStage.JOINED, reloaded.stage(player, settlement));
        assertEquals("Oakhaven", reloaded.registration(settlement).orElseThrow().name());
    }

    @Test void multiplayerFirstFounderWinsAndEachPlayerMustJoinPersonally() {
        var data = new KingdomOnboardingSavedData();
        UUID other = UUID.randomUUID();
        introduction(data, player); introduction(data, other);
        assertTrue(data.join(player, settlement, "Oakhaven"));
        assertEquals(OnboardingStage.NAMING, data.stage(other, settlement));
        assertTrue(data.join(other, settlement, "Trying to rename"));
        assertEquals("Oakhaven", data.registration(settlement).orElseThrow().name());
        assertEquals(player, data.registration(settlement).orElseThrow().founder());
        assertEquals(OnboardingStage.WELCOME, data.stage(UUID.randomUUID(), settlement));
    }

    @Test void duplicateNamesAreRejectedAndLegacyNamesRemainIntact() {
        var data = new KingdomOnboardingSavedData();
        introduction(data, player);
        assertTrue(data.join(player, settlement, "Oakhaven"));
        assertFalse(data.nameAvailable(UUID.randomUUID(), "OAKHAVEN"));
        UUID legacySettlement = UUID.randomUUID();
        assertTrue(data.enrollLegacy(player, legacySettlement, "Old Realm #1"));
        assertEquals("Old Realm #1", data.registration(legacySettlement).orElseThrow().name());
        assertEquals(OnboardingStage.JOINED, data.stage(player, legacySettlement));
        var reloaded = KingdomOnboardingSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(reloaded.registration(legacySettlement).orElseThrow().legacy());
        assertFalse(reloaded.enrollLegacy(player, legacySettlement, "New name"));
    }

    @Test void malformedOrDuplicatePersistentDataIsRejected() {
        var data = new KingdomOnboardingSavedData(); introduction(data, player);
        CompoundTag tag = data.save(new CompoundTag(), null);
        tag.getList("journeys", 10).add(tag.getList("journeys", 10).getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> KingdomOnboardingSavedData.load(tag, null));
        CompoundTag unsupported = new CompoundTag(); unsupported.putInt("schema_version", 2);
        assertThrows(IllegalArgumentException.class, () -> KingdomOnboardingSavedData.load(unsupported, null));
    }
}

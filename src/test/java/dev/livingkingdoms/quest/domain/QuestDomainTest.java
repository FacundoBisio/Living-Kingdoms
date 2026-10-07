package dev.livingkingdoms.quest.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuestDomainTest {
    @Test
    void deliveryTermsEnforcePositiveBoundedValues() {
        assertEquals(new QuestTerms(16, 8, 10), new QuestTerms(16, 8, 10));
        assertDoesNotThrow(() -> new QuestTerms(1, 1, 1));
        assertDoesNotThrow(() -> new QuestTerms(QuestTerms.MAX_ITEM_COUNT,
                QuestTerms.MAX_ITEM_COUNT, QuestTerms.MAX_REPUTATION_REWARD));
        assertThrows(IllegalArgumentException.class, () -> new QuestTerms(0, 8, 10));
        assertThrows(IllegalArgumentException.class, () -> new QuestTerms(16, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new QuestTerms(16, 8, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new QuestTerms(QuestTerms.MAX_ITEM_COUNT + 1, 8, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new QuestTerms(16, QuestTerms.MAX_ITEM_COUNT + 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new QuestTerms(16, 8, QuestTerms.MAX_REPUTATION_REWARD + 1));
    }

    @Test
    void progressRequiresTermsExactlyAfterAcceptanceAndSupportsSignedReputation() {
        QuestTerms terms = new QuestTerms(16, 8, 10);
        assertDoesNotThrow(() -> new PlayerSettlementProgress(QuestState.AVAILABLE, null, -5));
        for (QuestState state : new QuestState[]{QuestState.ACTIVE, QuestState.COMPLETED, QuestState.FAILED}) {
            assertDoesNotThrow(() -> new PlayerSettlementProgress(state, terms, 10));
            assertThrows(NullPointerException.class, () -> new PlayerSettlementProgress(state, null, 10));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSettlementProgress(QuestState.AVAILABLE, terms, 0));
        assertThrows(NullPointerException.class, () -> new PlayerSettlementProgress(null, null, 0));
    }

    @Test
    void persistedIdentifierDoesNotDependOnTranslatedTitle() {
        assertEquals("iron_shortage", QuestId.IRON_SHORTAGE.id());
        assertEquals(QuestType.RESOURCE_DELIVERY, QuestId.IRON_SHORTAGE.type());
        assertEquals(QuestId.IRON_SHORTAGE, QuestId.fromId("iron_shortage"));
        assertThrows(IllegalArgumentException.class, () -> QuestId.fromId("Iron Shortage"));
    }
}

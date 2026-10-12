package dev.livingkingdoms.ui;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UiSessionClosuresTest {
    @Test void canceledRepliesStayRejectedWhileFreshSessionsAndNewConnectionsRemainUsable() {
        var history=new UiSessionClosures(64); var canceled=UUID.randomUUID(); var fresh=UUID.randomUUID();
        history.close(canceled);
        assertTrue(history.isClosed(canceled)); assertFalse(history.isClosed(fresh));
        history.clear();
        assertFalse(history.isClosed(canceled)); assertFalse(history.isClosed(fresh));
    }
    @Test void repeatedCancellationDoesNotGrowHistoryAndOnlyOldestSessionsExpire() {
        var history=new UiSessionClosures(2); var old=UUID.randomUUID(); var recent=UUID.randomUUID(); var fresh=UUID.randomUUID();
        history.close(old); history.close(recent); history.close(recent);
        assertTrue(history.isClosed(old)); assertTrue(history.isClosed(recent));
        history.close(fresh);
        assertFalse(history.isClosed(old)); assertTrue(history.isClosed(recent)); assertTrue(history.isClosed(fresh));
    }
}

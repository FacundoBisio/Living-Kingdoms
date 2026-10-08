package dev.livingkingdoms.citizen;

import dev.livingkingdoms.settlement.domain.SettlementLifecycle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImmigrationPolicyTest {
    @Test void housingLifecycleEnableAndSharedPendingAllGateOpportunities() {
        assertTrue(ImmigrationPolicy.eligible(true,SettlementLifecycle.ESTABLISHED,2,0,1,2));
        assertFalse(ImmigrationPolicy.eligible(true,SettlementLifecycle.FOUNDING,2,0,1,2));
        assertFalse(ImmigrationPolicy.eligible(false,SettlementLifecycle.ESTABLISHED,2,0,1,2));
        assertFalse(ImmigrationPolicy.eligible(true,SettlementLifecycle.ESTABLISHED,0,0,1,2));
        assertFalse(ImmigrationPolicy.eligible(true,SettlementLifecycle.ESTABLISHED,2,2,1,2));
        assertFalse(ImmigrationPolicy.eligible(true,SettlementLifecycle.ESTABLISHED,1,0,2,2));
    }
    @Test void futureModifiersReceiveSharedFactorsAndCannotProduceInvalidChance() {
        var factors=ImmigrationPolicy.Factors.neutral(12,2);
        assertEquals(.25,ImmigrationPolicy.chance(.25,factors,(c,f)->c));
        assertEquals(1,ImmigrationPolicy.chance(.25,factors,(c,f)->f.reputation()));
        assertEquals(0,ImmigrationPolicy.chance(.25,factors,(c,f)->Double.NaN));
        assertEquals(0,ImmigrationPolicy.chance(.25,factors,(c,f)->-1));
    }
}

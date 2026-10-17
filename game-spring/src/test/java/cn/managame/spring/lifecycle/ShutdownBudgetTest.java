package cn.managame.spring.lifecycle;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShutdownBudgetTest {
    @Test void theDrainIsDerivedSoTheSumCannotDrift() {
        ShutdownBudget budget = ShutdownBudget.of(25_000, 5_000, 10_000);
        assertEquals(Duration.ofMillis(25_000), budget.total());
        assertEquals(Duration.ofMillis(5_000), budget.lead());
        assertEquals(Duration.ofMillis(10_000), budget.flushReserve());
        assertEquals(Duration.ofMillis(10_000), budget.drain());
        // The three phases add up to exactly the declared total; nothing is left implicit.
        assertEquals(budget.total(), budget.lead().plus(budget.drain()).plus(budget.flushReserve()));
    }
    @Test void theDefaultsFitBelowAKubernetesGracePeriodAndLeaveTheDrainTheRest() {
        ShutdownBudget budget = ShutdownBudget.of(ShutdownBudget.DEFAULT_TOTAL_MILLIS,
                ShutdownBudget.DEFAULT_LEAD_MILLIS, ShutdownBudget.DEFAULT_FLUSH_RESERVE_MILLIS);
        assertTrue(budget.total().toMillis() < 30_000, "the default total must stay under a 30 s grace period");
        assertEquals(Duration.ZERO, budget.lead());        // only the deployment knows its probe interval
        assertEquals(Duration.ofMillis(10_000), budget.flushReserve());
        assertEquals(Duration.ofMillis(15_000), budget.drain());
    }
    @Test void aSplitThatLeavesTheDrainNothingIsAStartupError() {
        // The flush keeps its slice, so an over-committed budget fails now instead of truncating later.
        assertThrows(IllegalArgumentException.class, () -> ShutdownBudget.of(10_000, 0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> ShutdownBudget.of(10_000, 5_000, 6_000));
        assertThrows(IllegalArgumentException.class, () -> ShutdownBudget.of(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ShutdownBudget.of(10_000, -1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> ShutdownBudget.of(10_000, 0, 0));
        // One millisecond of drain is enough to be a valid split.
        assertEquals(Duration.ofMillis(1), ShutdownBudget.of(10_000, 4_999, 5_000).drain());
    }
    @Test void thePhaseIsSetOnceAndReadableFromAnywhere() {
        ShutdownPhase phase = new ShutdownPhase();
        assertFalse(phase.leavingRotation());
        assertTrue(phase.leaveRotation());
        assertTrue(phase.leavingRotation());
        assertFalse(phase.leaveRotation());          // idempotent
        assertTrue(phase.leavingRotation());
    }
}

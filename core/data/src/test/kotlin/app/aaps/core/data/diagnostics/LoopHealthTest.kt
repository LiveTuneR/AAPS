package app.aaps.core.data.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LoopHealthTest {
    private val now = 1_780_000_000_000L
    private val fresh = LoopHealthState(newestRawBgTimestamp = now - 60_000, lastBgTriggeredRun = now - 60_000,
        autosensLastDataTimestamp = now - 60_000, lastCalculationSuccessTimestamp = now - 30_000)
    @Test fun `duration follows current calculation and rejects a future start`() {
        val running = fresh.copy(calculationRunning = true, currentCalculationStartedAt = now - 150_000, lastCalculationDurationMs = 10)
        assertEquals(150_000L, running.calculationDuration(now))
        assertEquals(210_000L, running.calculationDuration(now + 60_000))
        assertNull(running.copy(currentCalculationStartedAt = now + 1).calculationDuration(now))
        assertEquals(10L, running.copy(calculationRunning = false).calculationDuration(now))
    }
    @Test fun `fresh unknown stale and divergent inputs are distinguished`() {
        assertEquals(LoopHealthStatus.UNKNOWN, LoopHealthState().status(now))
        assertEquals(LoopHealthStatus.HEALTHY, fresh.status(now))
        assertEquals(LoopHealthStatus.STALE, fresh.copy(newestRawBgTimestamp = now - 600_000).status(now))
        assertEquals(LoopHealthStatus.DEGRADED, fresh.copy(lastBgTriggeredRun = now - 720_000).status(now))
        assertEquals(LoopHealthStatus.DEGRADED, fresh.copy(autosensLastDataTimestamp = now - 720_000).status(now))
        assertEquals(LoopHealthStatus.UNKNOWN, fresh.copy(newestRawBgTimestamp = now + 60_000).status(now))
        assertEquals(LoopHealthStatus.CALCULATING, fresh.copy(calculationRunning = true, currentCalculationStartedAt = now).status(now))
        assertEquals(LoopHealthStatus.DEGRADED, fresh.copy(calculationRunning = true, currentCalculationStartedAt = now - 180_000).status(now))
    }
    @Test fun `superseded completion cannot make current calculation appear complete`() {
        val tracker = LoopHealthTracker()
        tracker.started("main", 1, now)
        tracker.started("main", 2, now + 1)
        tracker.completed(1, now + 2)
        assertTrue(tracker.snapshot().calculationRunning)
        assertNull(tracker.snapshot().lastCalculationSuccessTimestamp)
        tracker.publishSkipped()
        tracker.completed(2, now + 3)
        assertFalse(tracker.snapshot().calculationRunning)
        assertEquals(1L, tracker.snapshot().supersededAdsPublishSkipCount)
        tracker.started("main", 3, now + 4)
        tracker.invalidated()
        tracker.completed(3, now + 5)
        assertEquals(now + 3, tracker.snapshot().lastCalculationSuccessTimestamp)
    }
}

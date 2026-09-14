package app.aaps.workflow

import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkflowPublicationTest : TestBase() {
    private fun prepare(): PrepareGraphDataWorker.PrepareGraphData = mock<PrepareGraphDataWorker.PrepareGraphData>().also {
        whenever(it.iobCobCalculator).thenReturn(mock())
    }
    @Test
    fun `superseded stopped and invalidated generations cannot publish`() {
        val slots = WorkflowChainData(aapsLogger)
        val first = slots.startMain(prepare(), mock())
        val second = slots.startMain(prepare(), mock())
        var publications = 0
        assertFalse(slots.publishIfCurrent(MAIN_CALCULATION, first, { false }) { publications++ })
        assertFalse(slots.publishIfCurrent(MAIN_CALCULATION, second, { true }) { publications++ })
        assertTrue(slots.publishIfCurrent(MAIN_CALCULATION, second, { false }) { publications++ })
        slots.invalidate(MAIN_CALCULATION)
        assertFalse(slots.publishIfCurrent(MAIN_CALCULATION, second, { false }) { publications++ })
        assertEquals(1, publications)
    }

    @Test
    fun `old calculation deliberately finishes after replacement for one thousand generations`() {
        val slots = WorkflowChainData(aapsLogger)
        val executor = Executors.newSingleThreadExecutor()
        try {
            repeat(1000) {
                val old = slots.startMain(prepare(), mock())
                val release = CountDownLatch(1)
                val pending = executor.submit<Boolean> {
                    check(release.await(5, TimeUnit.SECONDS))
                    slots.publishIfCurrent(MAIN_CALCULATION, old, { false }) { fail<Unit>("old ADS published") }
                }
                val current = slots.startMain(prepare(), mock())
                release.countDown()
                assertFalse(pending.get(5, TimeUnit.SECONDS))
                assertEquals(current, slots.activeGeneration(MAIN_CALCULATION))
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

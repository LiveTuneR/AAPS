package app.aaps.workflow

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.impl.WorkManagerImpl
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import javax.inject.Provider

/** Real enqueue/slot/publication code with a virtual execution boundary, not a device throughput claim. */
class ContinuousCgmWorkflowTest : TestBaseWithProfile() {
    private data class Run(val generation: Long, val finishes: Long, var cancelled: Boolean = false)

    private fun experiment(duration: Long, honorCancellation: Boolean): Int {
        val slots = WorkflowChainData(aapsLogger)
        val manager = mock<WorkManagerImpl>()
        val continuation = mock<WorkContinuation>()
        val calculator = mock<IobCobCalculator>()
        val overview = mock<OverviewData>()
        val cache = mock<OverviewDataCache>()
        val signals = mock<CalculationSignalsEmitter>()
        val workflow = CalculationWorkflowImpl(context, aapsLogger, dateUtil, slots, signals, Provider { cache })
        val running = mutableListOf<Run>()
        var now = 0L
        var accepted = 0
        var rejected = 0
        var enqueued = 0
        whenever(continuation.then(any<OneTimeWorkRequest>())).thenReturn(continuation)
        whenever(manager.beginUniqueWork(eq(MAIN_CALCULATION), any(), any<OneTimeWorkRequest>())).thenAnswer { call ->
            assertEquals(ExistingWorkPolicy.REPLACE, call.getArgument<ExistingWorkPolicy>(1))
            val request = call.getArgument<OneTimeWorkRequest>(2)
            val generation = request.workSpec.input.getLong(WorkflowChainData.GEN_KEY, -1)
            assertEquals(slots.activeGeneration(MAIN_CALCULATION), generation)
            assertTrue(slots.prepareFor(MAIN_CALCULATION, generation)!!.triggeredByNewBG)
            running.forEach { it.cancelled = honorCancellation }
            running += Run(generation, now + duration)
            enqueued++
            continuation
        }
        WorkManagerImpl.setDelegate(manager)
        try {
            // Keep sending at the horizon: there is deliberately no quiet tail to let the last run finish.
            for (input in 0L..1_800_000L step 60_000L) {
                running.filter { it.finishes <= input }.forEach { run ->
                    val published = slots.publishIfCurrent(MAIN_CALCULATION, run.generation, { run.cancelled }) {
                        assertNotNull(slots.postFor(MAIN_CALCULATION, run.generation))
                        accepted++
                    }
                    if (!published) rejected++
                }
                running.removeAll { it.finishes <= input }
                now = input
                workflow.runCalculation(MAIN_CALCULATION, calculator, overview, cache, signals, "continuous CGM", now, true, true)
            }
        } finally {
            WorkManagerImpl.setDelegate(null)
        }
        assertEquals(31, enqueued)
        verify(continuation, times(31)).enqueue()
        assertTrue(running.isNotEmpty())
        if (duration > 60_000) assertTrue(rejected > 0)
        println("ContinuousCgm durationMs=$duration honorCancellation=$honorCancellation inputs=$enqueued accepted=$accepted rejected=$rejected pending=${running.size}")
        return accepted
    }

    @Test fun `50 second control completes before the next minute input`() {
        assertEquals(30, experiment(50_000, true))
    }

    @Test fun `70 second calculations starve under continuous minute input`() {
        assertEquals(0, experiment(70_000, true))
        assertEquals(0, experiment(70_000, false))
    }

    @Test fun `90 second calculations starve under continuous minute input`() {
        assertEquals(0, experiment(90_000, true))
        assertEquals(0, experiment(90_000, false))
    }

    @Test fun `120 second calculations starve under continuous minute input`() {
        assertEquals(0, experiment(120_000, true))
        assertEquals(0, experiment(120_000, false))
    }
}

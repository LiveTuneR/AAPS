package app.aaps.workflow

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.impl.WorkManagerImpl
import app.aaps.core.data.workflow.CalculationJournal
import app.aaps.core.data.workflow.CalculationQueueState
import app.aaps.core.data.workflow.LatestPendingCalculation
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

/** Real enqueue/slot/publication code with virtual WM execution, not a device throughput claim. */
class ContinuousCgmWorkflowTest : TestBaseWithProfile() {
    private data class Run(val generation: Long, val finishes: Long)
    private class Journal : CalculationJournal {
        var saved: CalculationQueueState? = null
        override fun read() = saved
        override fun write(state: CalculationQueueState) { saved = state }
    }

    private fun experiment(duration: Long): Int {
        val slots = WorkflowChainData(aapsLogger)
        val manager = mock<WorkManagerImpl>()
        val continuation = mock<WorkContinuation>()
        val operation = mock<androidx.work.Operation>()
        whenever(operation.result).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(androidx.work.Operation.SUCCESS))
        whenever(continuation.enqueue()).thenReturn(operation)
        val calculator = mock<IobCobCalculator>()
        val overview = mock<OverviewData>()
        val cache = mock<OverviewDataCache>()
        val signals = mock<CalculationSignalsEmitter>()
        val queue = LatestPendingCalculation(Journal())
        val workflow = CalculationWorkflowImpl(context, aapsLogger, dateUtil, slots, signals, Provider { cache }).also {
            it.schedulerOverride = queue
            it.observeMainWork = false
        }
        var running: Run? = null
        var now = 1_000_000L
        var nextInput = now
        val horizon = now + 1_800_000L
        val accepted = mutableListOf<Long>()
        var enqueued = 0
        whenever(dateUtil.now()).thenAnswer { now }
        whenever(continuation.then(any<OneTimeWorkRequest>())).thenReturn(continuation)
        whenever(manager.beginUniqueWork(eq(MAIN_CALCULATION), any(), any<OneTimeWorkRequest>())).thenAnswer { call ->
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, call.getArgument<ExistingWorkPolicy>(1))
            val request = call.getArgument<OneTimeWorkRequest>(2)
            val generation = request.workSpec.input.getLong(WorkflowChainData.GEN_KEY, -1)
            assertEquals(slots.activeGeneration(MAIN_CALCULATION), generation)
            assertNotNull(slots.prepareFor(MAIN_CALCULATION, generation))
            assertNull(running, "Only one data-producing MAIN may be active")
            running = Run(generation, now + duration)
            enqueued++
            continuation
        }
        WorkManagerImpl.setDelegate(manager)
        try {
            while (minOf(nextInput, running?.finishes ?: Long.MAX_VALUE) <= horizon) {
                now = minOf(nextInput, running?.finishes ?: Long.MAX_VALUE)
                running?.takeIf { it.finishes == now }?.let { run ->
                    val bg = requireNotNull(queue.snapshot().active?.intent?.rawBgTimestamp)
                    assertTrue(slots.publishIfCurrent(MAIN_CALCULATION, run.generation, { false }) {
                        assertNotNull(slots.postFor(MAIN_CALCULATION, run.generation))
                        assertTrue(slots.claimBgIfCurrent(MAIN_CALCULATION, run.generation, bg, 0))
                        assertFalse(slots.claimBgIfCurrent(MAIN_CALCULATION, run.generation, bg, 0))
                        accepted += bg
                    })
                    running = null
                    workflow.mainFinished(run.generation, true)
                }
                if (now == nextInput) {
                    workflow.runCalculation(MAIN_CALCULATION, calculator, overview, cache, signals, "continuous CGM", now, true, true,
                        rawBgTimestamp = now)
                    nextInput += 60_000
                }
            }
        } finally { WorkManagerImpl.setDelegate(null) }
        assertEquals((1_800_000L / maxOf(duration,60_000)).toInt(), accepted.size)
        assertEquals(accepted.size, accepted.distinct().size)
        assertTrue(accepted.zipWithNext().all { (a,b) -> b > a && b - a <= duration + 60_000 })
        assertEquals(0L, queue.snapshot().cancelledCount)
        verify(continuation, times(enqueued)).enqueue()
        println("ContinuousCgm durationMs=$duration inputs=31 completed=${accepted.size} enqueued=$enqueued latestPending=${queue.snapshot().pending?.rawBgTimestamp} coalesced=${queue.snapshot().coalescedBgCount}")
        return accepted.size
    }

    @Test fun `50 second control completes every minute`() { assertEquals(30, experiment(50_000)) }
    @Test fun `70 second calculations make bounded progress`() { assertEquals(25, experiment(70_000)) }
    @Test fun `90 second calculations make bounded progress`() { assertEquals(20, experiment(90_000)) }
    @Test fun `120 second calculations make bounded progress`() { assertEquals(15, experiment(120_000)) }

    @Test fun `repeated worker failures retain newest reload without duplicate claimed BG`() = kotlinx.coroutines.test.runTest {
        val slots=WorkflowChainData(aapsLogger)
        val manager=mock<WorkManagerImpl>()
        val continuation=mock<WorkContinuation>()
        val operation=mock<androidx.work.Operation>()
        whenever(operation.result).thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(androidx.work.Operation.SUCCESS))
        whenever(continuation.enqueue()).thenReturn(operation)
        whenever(continuation.then(any<OneTimeWorkRequest>())).thenReturn(continuation)
        whenever(manager.beginUniqueWork(eq(MAIN_CALCULATION),any(),any<OneTimeWorkRequest>())).thenReturn(continuation)
        val queue=LatestPendingCalculation(Journal())
        val cache=mock<OverviewDataCache>()
        val signals=mock<CalculationSignalsEmitter>()
        val workflow=CalculationWorkflowImpl(context,aapsLogger,dateUtil,slots,signals,Provider { cache }).also {
            it.schedulerOverride=queue; it.observeMainWork=false; it.observerScope=this
        }
        WorkManagerImpl.setDelegate(manager)
        try {
            workflow.runCalculation(MAIN_CALCULATION,mock(),mock(),cache,signals,"retry test",100,true,true,rawBgTimestamp=100)
            assertTrue(queue.claimBg(queue.snapshot().active!!.generation,100))
            repeat(3) {
                val generation=queue.snapshot().active!!.generation
                workflow.mainFinished(generation,false)
                assertEquals(100L,queue.snapshot().pending?.rawBgTimestamp)
                assertNull(queue.snapshot().active)
                testScheduler.advanceTimeBy(5001)
                testScheduler.runCurrent()
                assertTrue(queue.snapshot().active!!.intent.recovered)
                assertFalse(queue.claimBg(queue.snapshot().active!!.generation,100))
            }
            workflow.mainFinished(queue.snapshot().active!!.generation,true)
            assertNull(queue.snapshot().active)
        } finally { WorkManagerImpl.setDelegate(null) }
    }

    @Test fun `legacy REPLACE counterexample still starves under continuous input`() {
        for (duration in listOf(70_000L,90_000L,120_000L)) {
            var completion: Long? = null
            var completed = 0
            for (now in 0L..1_800_000L step 60_000L) {
                if (completion?.let { it <= now } == true) completed++
                completion = now + duration
            }
            assertEquals(0,completed)
        }
    }
}

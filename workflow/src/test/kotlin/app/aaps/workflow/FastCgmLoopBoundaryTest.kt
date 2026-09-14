package app.aaps.workflow

import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant

/** Real bucketing, actualBg, generation publication and PostCalculationWorker; only Loop/pump are mocked. */
class FastCgmLoopBoundaryTest : TestBaseWithProfile() {
    private val start = Instant.parse("2026-09-12T12:01:17Z").toEpochMilli()

    @Test fun `sixty second stream reaches Loop every minute over thirty minutes`() = stream(60_000L, false)
    @Test fun `two minute stream reaches Loop every reading over thirty minutes`() = stream(120_000L, false)
    @Test fun `superseded sixty second run reloads same live store and reaches Loop`() = stream(60_000L, true)
    @Test fun `superseded two minute run reloads same live store and reaches Loop`() = stream(120_000L, true)
    @Test fun `native five minute source and duplicate dispatch remain correct`() = stream(300_000L, false)

    private fun stream(step: Long, supersede: Boolean) = runTest {
        var now = start
        var live = AutosensDataStoreObject { now }
        val chains = WorkflowChainData(aapsLogger)
        val loop = mock<Loop>()
        val params = mock<WorkerParameters>()
        val cache = mock<OverviewDataCache>()
        whenever(cache.timeRangeFlow).thenReturn(MutableStateFlow(null))
        val overview = mock<OverviewData>()
        val signals = mock<CalculationSignalsEmitter>()
        val post = PostCalculationWorker.PostCalculationData(overview, cache, signals, true, true)
        fun prepare() = PrepareGraphDataWorker.PrepareGraphData(iobCobCalculator, overview, cache, signals,
            "fast-cgm-test", now, true, true, true, false)
        var claimed = 0L
        whenever(loop.lastBgTriggeredRun).thenAnswer { claimed }
        doAnswer { claimed = it.getArgument(0); null }.whenever(loop).lastBgTriggeredRun = any()
        whenever(iobCobCalculator.ads).thenAnswer { live }
        whenever(config.APS).thenReturn(false)
        val observed = mutableListOf<Long>()
        whenever(loop.invoke(any(), any(), any())).thenAnswer { observed.add(live.actualBg()!!.timestamp); Unit }
        for (elapsed in 0L..1_800_000L step step) {
            now = start + elapsed
            whenever(dateUtil.now()).thenReturn(now)
            fun load() {
                live.bgReadings = (0..40).map { i -> GV(id = 1 + (now - start) / step + 40 - i,
                    timestamp = now - i * step, value = 140.0 - i, raw = null, noise = null,
                    sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT) }
                live.createBucketedData(aapsLogger, dateUtil)
            }
            if (supersede) {
                load()
                val stale = live.clone()
                val staleGeneration = chains.startMain(prepare(), post)
                chains.invalidate(MAIN_CALCULATION)
                assertFalse(chains.publishIfCurrent(MAIN_CALCULATION, staleGeneration, { false }) { live = stale as AutosensDataStoreObject })
                assertEquals(-1L, live.referenceTime)
                // New raw input on the exact same store after a skipped clone publication.
                now += step
            }
            load()
            val generation = chains.startMain(prepare(), post)
            val completed = live.clone() as AutosensDataStoreObject
            assertTrue(chains.publishIfCurrent(MAIN_CALCULATION, generation, { false }) {
                completed.markCalculationCompleted()
                live = completed
            })
            assertEquals(-1L, live.referenceTime)
            assertEquals(now, live.actualBg()!!.timestamp)
            assertNotNull(live.lastBucketPass?.referenceTimeUsed)
            whenever(params.inputData).thenReturn(workDataOf(WorkflowChainData.JOB_KEY to MAIN_CALCULATION, WorkflowChainData.GEN_KEY to generation))
            fun worker() = PostCalculationWorker(context, params, aapsLogger, fabricPrivacy, chains,
                iobCobCalculator, loop, mock(), config, mock(), profileUtil, preferences)
            worker().doWorkAndLog()
            worker().doWorkAndLog() // Same completed BG is never claimed twice.
            assertEquals(now, observed.last())
        }
        assertEquals((1_800_000L / step + 1).toInt(), observed.size)
        assertEquals(observed.size, observed.distinct().size)
        assertTrue(observed.zipWithNext().all { (a, b) -> b - a == step })
    }
}

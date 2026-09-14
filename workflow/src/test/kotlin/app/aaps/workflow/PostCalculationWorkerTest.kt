package app.aaps.workflow

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.widget.WidgetUpdater
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.times
import kotlin.test.assertIs

class PostCalculationWorkerTest : TestBaseWithProfile() {

    @Mock lateinit var workflowChainData: WorkflowChainData
    @Mock lateinit var loop: Loop
    @Mock lateinit var widgetUpdater: WidgetUpdater
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var workerParameters: WorkerParameters

    private fun worker() =
        PostCalculationWorker(
            context, workerParameters, aapsLogger, fabricPrivacy, workflowChainData, iobCobCalculator, loop,
            widgetUpdater, config, processedDeviceStatusData, profileUtil, preferences
        )

    private fun dataWith(triggeredByNewBG: Boolean, runLoopAndWidgetPhase: Boolean): PostCalculationWorker.PostCalculationData {
        val cache = mock<OverviewDataCache>()
        whenever(cache.timeRangeFlow).thenReturn(MutableStateFlow(null))
        return PostCalculationWorker.PostCalculationData(
            overviewData = mock<OverviewData>(),
            cache = cache,
            signals = mock<CalculationSignalsEmitter>(),
            triggeredByNewBG = triggeredByNewBG,
            runLoopAndWidgetPhase = runLoopAndWidgetPhase
        )
    }

    @BeforeEach
    fun setup() {
        whenever(workerParameters.inputData).thenReturn(workDataOf())
        whenever(config.APS).thenReturn(false)
    }

    @Test
    fun `missing or stale data returns failure`() = runTest {
        whenever(workflowChainData.postFor(anyOrNull(), any())).thenReturn(null)

        assertIs<ListenableWorker.Result.Failure>(worker().doWorkAndLog())
    }

    @Test
    fun `predictions only phase returns success without loop or widget`() = runTest {
        val data = dataWith(triggeredByNewBG = false, runLoopAndWidgetPhase = false)
        whenever(workflowChainData.postFor(anyOrNull(), any())).thenReturn(data)

        val result = worker().doWorkAndLog()

        Assertions.assertEquals(ListenableWorker.Result.success(), result)
        verify(loop, never()).invoke(any(), any(), any())
        verify(widgetUpdater, never()).update(any())
    }

    @Test
    fun `full phase invokes loop on new bg and updates widget`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(5000L)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
        whenever(loop.lastBgTriggeredRun).thenReturn(0L)
        // A DB replacement must retain the loop opportunity even without the NewBG flag.
        val data = dataWith(triggeredByNewBG = false, runLoopAndWidgetPhase = true)
        whenever(workflowChainData.postFor(anyOrNull(), any())).thenReturn(data)

        val result = worker().doWorkAndLog()

        Assertions.assertEquals(ListenableWorker.Result.success(), result)
        verify(loop).invoke(any(), any(), any())
        verify(widgetUpdater).update("WorkFlow")
    }

    @Test
    fun `duplicate completed chains do not invoke twice for one BG`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = InMemoryGlucoseValue(5000L, 100.0, sourceSensor = app.aaps.core.data.model.SourceSensor.UNKNOWN)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
        var claimed = 0L
        whenever(loop.lastBgTriggeredRun).thenAnswer { claimed }
        doAnswer { claimed = it.getArgument(0); null }.whenever(loop).lastBgTriggeredRun = any()
        val data = dataWith(false, true)
        whenever(workflowChainData.postFor(anyOrNull(), any())).thenReturn(data)
        repeat(100) { worker().doWorkAndLog() }
        verify(loop, times(1)).invoke(any(), any(), any())
        Assertions.assertEquals(5000L, claimed)
    }

    @Test
    fun `no actual BG never invokes loop`() = runTest {
        val ads = mock<AutosensDataStore>()
        whenever(iobCobCalculator.ads).thenReturn(ads)
        val data = dataWith(false, true)
        whenever(workflowChainData.postFor(anyOrNull(), any())).thenReturn(data)
        worker().doWorkAndLog()
        verify(loop, never()).invoke(any(), any(), any())
    }
}

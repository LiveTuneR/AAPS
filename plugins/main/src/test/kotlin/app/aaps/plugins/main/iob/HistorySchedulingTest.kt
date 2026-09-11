package app.aaps.plugins.main.iob

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class HistorySchedulingTest : TestBaseWithProfile() {
    @Test
    fun `scheduler exists when first database collector subscribes`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val workflow = mock<CalculationWorkflow>()
        val bus = mock<app.aaps.core.interfaces.rx.bus.RxBus>()
        whenever(bus.toObservable(any<Class<Any>>())).thenReturn(io.reactivex.rxjava3.core.Observable.never())
        val schedulers = mock<app.aaps.core.interfaces.rx.AapsSchedulers>()
        whenever(schedulers.io).thenReturn(io.reactivex.rxjava3.schedulers.Schedulers.trampoline())
        val plugin = IobCobCalculatorPlugin(aapsLogger, schedulers, bus, preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), workflow, decimalFormatter, processedTbrEbData, mock(), mock())
        var checked = false
        whenever(persistence.observeChanges(any<Class<app.aaps.core.data.model.GV>>())).thenAnswer {
            // observeChanges can return a flow that immediately emits on subscription.
            assertNotNull(plugin.javaClass.getDeclaredField("historyWorker").apply { isAccessible = true }.get(plugin))
            plugin.scheduleHistoryDataChange(1_000_000, true, true)
            checked = true
            kotlinx.coroutines.flow.emptyFlow<List<app.aaps.core.data.model.GV>>()
        }
        try {
            plugin.onStart()
            assertTrue(checked)
            assertNotNull(plugin.javaClass.getDeclaredField("scheduledData").apply { isAccessible = true }.get(plugin))
        } finally { plugin.onStop() }
    }

    @Test
    fun `history exception releases debounce state and next event can run`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val workflow = mock<CalculationWorkflow>()
        val plugin = IobCobCalculatorPlugin(aapsLogger, mock(), mock(), preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), workflow, decimalFormatter, processedTbrEbData, mock(), mock())
        val executor = mock<ScheduledExecutorService>()
        val pending = mutableListOf<Runnable>()
        whenever(executor.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())).thenAnswer {
            pending.add(it.getArgument(0))
            mock<ScheduledFuture<Any>>()
        }
        plugin.javaClass.getDeclaredField("historyWorker").apply { isAccessible = true }.set(plugin, executor)
        whenever(persistence.clearCachedTddData(any())).thenThrow(IllegalStateException("synthetic"))
        plugin.scheduleHistoryDataChange(1_000_000, true, true)
        pending.removeAt(0).run()
        for (name in listOf("scheduledData", "scheduledHistoryPost")) {
            assertNull(plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(plugin))
        }
        plugin.scheduleHistoryDataChange(2_000_000, true, true)
        assertEquals(1, pending.size)
        pending.removeAt(0).run()
        verify(executor, times(2)).schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        verify(workflow, never()).runCalculation(any(), any(), any(), any(), any(), any(), any(), any(), any())
        plugin.onStop()
    }
}

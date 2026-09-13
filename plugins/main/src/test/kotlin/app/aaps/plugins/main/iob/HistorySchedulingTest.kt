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
    @Test fun `direct EPS invalidation clears TDD from affected midnight before restart`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val workflow = mock<CalculationWorkflow>()
        val plugin = IobCobCalculatorPlugin(aapsLogger, mock(), mock(), preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), workflow, decimalFormatter, processedTbrEbData, mock(), javax.inject.Provider { mock() })
        val affected = java.time.Instant.parse("2026-09-12T13:42:17Z").toEpochMilli()
        val method = plugin.javaClass.declaredMethods.single { it.name == "newHistoryData" }
        method.isAccessible = true
        method.invoke(plugin, affected, false, false, true, null)
        verify(persistence).clearCachedTddData(app.aaps.core.interfaces.utils.MidnightTime.calc(affected))
        verify(workflow).stopCalculation(CalculationWorkflow.MAIN_CALCULATION, "onEventNewHistoryData", affected - 300_000)
        verify(workflow).runCalculation(any(), any(), any(), any(), any(), eq("DBChange"), any(), eq(false), eq(false), eq(affected - 300_000), isNull())
        verifyNoMoreInteractions(persistence)
    }

    @Test fun `debounced history invalidation clears same midnight exactly once`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val workflow = mock<CalculationWorkflow>()
        val plugin = IobCobCalculatorPlugin(aapsLogger, mock(), mock(), preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), workflow, decimalFormatter, processedTbrEbData, mock(), javax.inject.Provider { mock() })
        val executor = mock<ScheduledExecutorService>()
        val actions = mutableListOf<Runnable>()
        whenever(executor.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())).thenAnswer {
            actions.add(it.getArgument(0)); mock<ScheduledFuture<Any>>()
        }
        plugin.javaClass.getDeclaredField("historyWorker").apply { isAccessible = true }.set(plugin, executor)
        val affected = java.time.Instant.parse("2026-09-12T13:42:17Z").toEpochMilli()
        plugin.scheduleHistoryDataChange(affected, false, false)
        actions.single().run()
        verify(workflow).runCalculation(any(), any(), any(), any(), any(), eq("DBChange"), any(), eq(false), eq(false), eq(affected - 300_000), isNull())
        verify(persistence).clearCachedTddData(app.aaps.core.interfaces.utils.MidnightTime.calc(affected))
        verifyNoMoreInteractions(persistence)
        plugin.onStop()
    }
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
    fun `history failure retries without new event and retains earliest invalidation across new BG`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val workflow = mock<CalculationWorkflow>()
        val plugin = IobCobCalculatorPlugin(aapsLogger, mock(), mock(), preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), workflow, decimalFormatter, processedTbrEbData, mock(), javax.inject.Provider { mock() })
        val executor = mock<ScheduledExecutorService>()
        val pending = mutableListOf<Runnable>()
        whenever(executor.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())).thenAnswer {
            pending.add(it.getArgument(0))
            mock<ScheduledFuture<Any>>()
        }
        plugin.javaClass.getDeclaredField("historyWorker").apply { isAccessible = true }.set(plugin, executor)
        whenever(persistence.clearCachedTddData(any())).thenThrow(IllegalStateException("synthetic"))
        plugin.scheduleHistoryDataChange(1_000_000, true, false)
        pending.removeAt(0).run()
        for (name in listOf("scheduledData", "scheduledHistoryPost")) {
            assertNotNull(plugin.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(plugin))
        }
        plugin.scheduleHistoryDataChange(2_000_000, true, true,therapyChange=false,newestBgTimestamp=2_000_000)
        assertEquals(1, pending.size)
        org.mockito.kotlin.doAnswer { Unit }.whenever(persistence).clearCachedTddData(any())
        pending.removeAt(0).run()
        verify(executor, times(2)).schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        verify(workflow).runCalculation(any(), any(), any(), any(), any(), any(), any(), eq(true), eq(false), eq(700_000L),eq(2_000_000L))
        assertNull(plugin.javaClass.getDeclaredField("scheduledData").apply { isAccessible=true }.get(plugin))
        plugin.onStop()
    }
}

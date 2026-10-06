package app.aaps.ui.compose.overview.enhanced

import androidx.lifecycle.ViewModelStore
import app.aaps.core.data.diagnostics.EnergyRuntimeCounters
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.BooleanKey
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.kotlin.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OverviewEnergyLifecycleTest : TestBaseWithProfile() {
    @Test fun `hidden overview retains activity collection but does no two second display work`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val persistence = mock<PersistenceLayer>()
        val activities = mock<ActivityContextRepository>()
        whenever(persistence.observeChanges(anyOrNull<Class<*>>())).thenReturn(emptyFlow())
        whenever(activities.changes).thenReturn(MutableStateFlow(0L))
        whenever(preferences.observe(BooleanKey.OverviewEnhanced)).thenReturn(MutableStateFlow(true))
        val owner = ViewModelStore()
        fun ticks() = ((EnergyRuntimeCounters.snapshot()["counters"] as Map<*, *>)["timer.overviewVisible"] as Long?) ?: 0L
        try {
            val before = ticks()
            val vm = OverviewDashboardViewModel(iobCobCalculator, loop, activePlugin, profileFunction, profileUtil,
                persistence, preferences, rh, aapsLogger, activities, rxBus)
            owner.put("overview", vm)
            verifyBlocking(activities, org.mockito.Mockito.timeout(5000)) { refresh(any()) }
            delay(2200)
            assertEquals(before, ticks())
            val visible = launch { vm.state.collect { } }
            delay(2200)
            assertTrue(ticks() > before)
            visible.cancel(); visible.join()
            delay(100)
            val hidden = ticks()
            delay(2200)
            assertEquals(hidden, ticks())
            verifyBlocking(activities, times(1)) { refresh(any()) }
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}

package app.aaps.implementation.db

import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class ProcessedTbrEbRangeTest : TestBaseWithProfile() {
    @Test fun `range matches point lookups including left overlap end exclusion and precedence`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val start = now - 3_600_000
        val end = now
        val temps = listOf(
            TB(timestamp = start - 600_000, duration = 1_800_000, rate = 1.2, isAbsolute = true, type = TB.Type.NORMAL),
            TB(timestamp = start + 600_000, duration = 600_000, rate = 50.0, isAbsolute = false, type = TB.Type.NORMAL)
        ).sortedByDescending { it.timestamp }
        val pump = mock<app.aaps.core.interfaces.pump.PumpWithConcentration>()
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.isFakingTempsByExtendedBoluses).thenReturn(true)
        val extended = listOf(EB(timestamp = start - 1_200_000, duration = 4_800_000, amount = 2.0))
        whenever(persistence.getTemporaryBasalsActiveBetweenTimeAndTime(start, end)).thenReturn(temps)
        whenever(persistence.getExtendedBolusesActiveBetweenTimeAndTime(start, end)).thenReturn(extended)
        whenever(persistence.getTemporaryBasalActiveAt(any())).thenAnswer { call ->
            val t = call.getArgument<Long>(0); temps.firstOrNull { it.timestamp <= t && it.end > t }
        }
        whenever(persistence.getExtendedBolusActiveAt(any())).thenAnswer { call ->
            val t = call.getArgument<Long>(0); extended.firstOrNull { it.timestamp <= t && it.end > t }
        }
        whenever(profileFunction.getProfile(any())).thenReturn(effectiveProfile)
        val processed = ProcessedTbrEbDataImpl(persistence, activePlugin, profileFunction)
        val range = processed.getTempBasalsIncludingConvertedExtended(start, end)
        for (t in start..end step 60_000) assertEquals(processed.getTempBasalIncludingConvertedExtended(t), range.at(t), "time=$t")
        verify(persistence, times(1)).getTemporaryBasalsActiveBetweenTimeAndTime(start, end)
        verify(persistence, times(1)).getExtendedBolusesActiveBetweenTimeAndTime(start, end)
        assertEquals(50.0, range.at(start + 600_000)!!.rate)
        assertNull(range.at(end))
    }
}

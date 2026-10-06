package app.aaps.plugins.main.iob

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class HistoricalIobEquivalenceTest : TestBaseWithProfile() {
    @Test fun `same virtual time has identical full insulin totals cold warm and after correction`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val plugin = IobCobCalculatorPlugin(aapsLogger, mock(), mock(), preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), mock(), decimalFormatter, processedTbrEbData, mock(), javax.inject.Provider { mock() })
        whenever(preferences.get(DoubleKey.ApsAmaBolusSnoozeDivisor)).thenReturn(2.0)
        whenever(profileFunction.getProfile()).thenReturn(effectiveProfile)
        whenever(profileFunction.getProfile(any())).thenReturn(effectiveProfile)
        var amount = 1.25
        whenever(persistence.getBolusesFromTime(any(), any())).thenAnswer {
            listOf(BS(timestamp = now - 3_600_000, amount = amount, type = BS.Type.NORMAL, iCfg = someICfg))
        }
        whenever(persistence.getTemporaryBasalsStartingFromTimeToTime(any(), any(), any())).thenAnswer {
            listOf(TB(timestamp = now - 7_200_000, duration = 1_800_000, rate = 1.5, isAbsolute = true, type = TB.Type.NORMAL))
        }
        whenever(persistence.getExtendedBolusesStartingFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        val time = now - 600_000
        val cold = plugin.calculateFromTreatmentsAndTemps(time, effectiveProfile)
        plugin.bgDataReloaded()
        val warm = plugin.calculateFromTreatmentsAndTemps(time, effectiveProfile)
        assertEquals(cold, warm)
        verify(persistence, times(1)).getBolusesFromTime(any(), any())
        assertEquals(1L, plugin.cacheStats().hits)
        amount = 2.5
        // This fence occurs before the debounced history reload, also when the worker has not started.
        plugin.scheduleHistoryDataChange(now - 3_600_000, false, false)
        val corrected = plugin.calculateFromTreatmentsAndTemps(time, effectiveProfile)
        plugin.clearCache()
        assertEquals(corrected, plugin.calculateFromTreatmentsAndTemps(time, effectiveProfile))
        assertNotEquals(cold.iob, corrected.iob)
    }

    @Test fun `basal point and range use each callers profile at identical rounded time`() = runTest {
        val persistence = mock<PersistenceLayer>()
        val plugin = IobCobCalculatorPlugin(aapsLogger, mock(), mock(), preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistence, mock(), mock(), decimalFormatter, processedTbrEbData, mock(), javax.inject.Provider { mock() })
        val first = mock<app.aaps.core.interfaces.profile.Profile>()
        val second = mock<app.aaps.core.interfaces.profile.Profile>()
        whenever(first.getBasal(any())).thenReturn(0.96)
        whenever(second.getBasal(any())).thenReturn(1.44)
        whenever(processedTbrEbData.getTempBasalsIncludingConvertedExtended(any(), any())).thenReturn(object : app.aaps.core.interfaces.db.ProcessedTbrEbData.TempBasalsInRange {
            override suspend fun at(timestamp: Long): TB? = null
        })
        val range = plugin.getBasalDataForRange(now - 600_000, now)
        for (profile in listOf(first, second, first)) {
            assertEquals(plugin.getBasalData(profile, now - 600_000).basal, range.at(profile, now - 600_000).basal)
        }
        assertEquals(1.44, plugin.getBasalData(second, now - 600_000).basal)
    }
}

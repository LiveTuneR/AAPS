package app.aaps.plugins.sensitivity

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.keys.DoubleKey
import app.aaps.implementation.iob.AutosensDataObject
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.SourceSensor
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.system.measureNanoTime

class Oref1EquivalenceTest : TestBaseWithProfile() {
    @Test
    fun `all exported sensitivity fields equal frozen baseline with large history and corrections`() {
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(1.2)
        val profile = mock<EffectiveProfile>()
        whenever(profile.getMaxDailyBasal()).thenReturn(1.0)
        val optimized = SensitivityOref1Plugin(aapsLogger, rh, preferences, dateUtil)
        val legacy = LegacySensitivityOref1Plugin(aapsLogger, rh, preferences, dateUtil)
        for (size in listOf(3, 24, 288, 4605, 100_000)) {
            val table = LongSparseArray<AutosensData>()
            val end = 1_780_000_020_000L
            repeat(size) { i ->
                val row = AutosensDataObject(aapsLogger, preferences, dateUtil).apply {
                    time = end - (size - i - 1) * 300_000L
                    bg = if (i % 7 == 0) 70.0 else 110.0
                    deviation = (i % 11 - 5) / 2.0
                    validDeviation = i % 13 != 0
                    sens = 50.0
                    cob = 12.0
                    pastSensitivity = if (i % 2 == 0) "+" else "-"
                    if (i % 17 == 0) extraDeviation.add(-0.3)
                }
                table.put(row.time, row)
            }
            val ads = AutosensDataStoreObject().apply {
                autosensDataTable = table
                bucketedData = (0 until size).map { i -> InMemoryGlucoseValue(end - i * 300_000L, 100.0, sourceSensor = SourceSensor.UNKNOWN) }.toMutableList()
            }
            for (hours in listOf(0, 8, 24, 72)) {
                val from = end - hours * 3_600_000L
                // toTime intentionally precedes some rows; both bounds must be honored.
                for (to in listOf(end, end - 300_000L)) {
                    val sites = listOf(TE(timestamp = end - 1_800_000L, type = TE.Type.CANNULA_CHANGE, glucoseUnit = app.aaps.core.data.model.GlucoseUnit.MGDL))
                    val switches = emptyList<PS>()
                    val expected = legacy.detectSensitivity(ads, from, to, profile, sites, switches)
                    val actual = optimized.detectSensitivity(ads, from, to, profile, sites, switches)
                    assertEquals(expected, actual, "size=$size hours=$hours to=$to")
                }
            }
            if (size >= 288) {
                val from = end - 24 * 3_600_000L
                repeat(3) { legacy.detectSensitivity(ads, from, end, profile, emptyList(), emptyList()) }
                repeat(3) { optimized.detectSensitivity(ads, from, end, profile, emptyList(), emptyList()) }
                val before = measureNanoTime { repeat(10) { legacy.detectSensitivity(ads, from, end, profile, emptyList(), emptyList()) } } / 10
                val after = measureNanoTime { repeat(10) { optimized.detectSensitivity(ads, from, end, profile, emptyList(), emptyList()) } } / 10
                println("OREF1_BENCH size=$size legacyNs=$before optimizedNs=$after fixture=real_ads_synthetic_data_logging_enabled")
            }
        }
    }
}

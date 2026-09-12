package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Characterizes the conflicting designs in the supplied loop-5min note, without changing therapy. */
class BucketCadenceCharacterizationTest : TestBaseWithProfile() {
    private val start = 1_780_000_000_000L
    private fun readings(minute: Int, interval: Int) = (0..120).map { index ->
        GV(timestamp = start + (minute - index * interval) * 60_000L, value = 100.0 + minute - index * interval,
           raw = null, noise = null, sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)
    }

    @Test
    fun `legacy persistent anchor reconstruction holds latest bucket until next five minute slot`() {
        for (interval in listOf(1, 2)) {
            val store = AutosensDataStoreObject()
            for (minute in 0..120 step interval) {
                // Deliberately reconstruct the old persistent policy, not production behavior.
                store.referenceTime = start
                store.bgReadings = readings(minute, interval)
                store.createBucketedData(aapsLogger, dateUtil)
                val timestamp = store.lastBg()!!.timestamp
                assertEquals(start + minute / 5 * 300_000L, timestamp)
                assertTrue(store.bgReadings.first().timestamp - timestamp in 0..240_000L)
            }
        }
    }

    @Test
    fun `baseline clone drops anchor so completed publications track incoming readings`() {
        for (interval in listOf(1, 2)) {
            var store = AutosensDataStoreObject()
            for (minute in 0..120 step interval) {
                store.bgReadings = readings(minute, interval)
                store.createBucketedData(aapsLogger, dateUtil)
                assertEquals(store.bgReadings.first().timestamp, store.lastBg()!!.timestamp)
                assertEquals(store.bgReadings.first().value, store.lastBg()!!.value)
                store = store.clone() as AutosensDataStoreObject
                assertEquals(-1L, store.referenceTime)
            }
        }
    }
}

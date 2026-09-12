package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class FastCgmAnchorTest : TestBaseWithProfile() {
    private val start = Instant.parse("2026-09-12T12:01:17Z").toEpochMilli()
    private fun readings(time: Long, step: Long, value: Double = 140.0, jitter: Boolean = false) = (0..36).map { index ->
        GV(timestamp = time - index * step - if (jitter && index % 2 == 1) 10_000L else 0L,
            value = value - index, raw = null, noise = null, sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)
    }

    private fun fastStream(step: Long, publishCopies: Boolean) {
        var live = AutosensDataStoreObject()
        var lastLoopBg = Long.MIN_VALUE
        var actionable = 0
        for (elapsed in 0L..1_800_000L step step) {
            val timestamp = start + elapsed
            val value = 140.0 + elapsed / step % 10
            live.bgReadings = readings(timestamp, step, value)
            live.createBucketedData(aapsLogger, dateUtil)
            val newest = live.lastBg()!!
            assertEquals(timestamp, live.bgReadings.first().timestamp)
            assertEquals(timestamp, newest.timestamp, "newest bucket at elapsed=$elapsed")
            assertEquals(value, newest.value)
            assertFalse(newest.filledGap)
            assertTrue(live.bucketedData!!.zipWithNext().all { (a, b) -> a.timestamp > b.timestamp })
            assertTrue(live.bucketedData!!.all { it.timestamp <= timestamp })
            assertTrue(newest.timestamp > lastLoopBg)
            lastLoopBg = newest.timestamp
            actionable++
            if (publishCopies) live = live.clone() as AutosensDataStoreObject
        }
        assertEquals((1_800_000L / step + 1).toInt(), actionable)
    }

    @Test fun `60 second CGM advances every completed publication for 30 minutes`() = fastStream(60_000, true)
    @Test fun `120 second CGM advances every completed publication for 30 minutes`() = fastStream(120_000, true)
    @Test fun `superseded publication followed by live reload keeps newest 60 second BG`() = fastStream(60_000, false)
    @Test fun `superseded publication followed by live reload keeps newest 120 second BG`() = fastStream(120_000, false)

    @Test fun `clone never imports an unfinished or legacy pass anchor`() {
        val source = AutosensDataStoreObject().apply { referenceTime = start }
        assertEquals(-1L, (source.clone() as AutosensDataStoreObject).referenceTime)
    }

    @Test fun `anchor ends at pass boundary including empty input`() {
        val source = AutosensDataStoreObject()
        source.bgReadings = readings(start, 60_000)
        source.createBucketedData(aapsLogger, dateUtil)
        assertEquals(-1L, source.referenceTime)
        source.referenceTime = start
        source.bgReadings = emptyList()
        source.createBucketedData(aapsLogger, dateUtil)
        assertEquals(-1L, source.referenceTime)
    }

    @Test fun `exact and near five minute data preserve order values and phase`() {
        for (jitter in listOf(false, true)) {
            val store = AutosensDataStoreObject()
            for (minute in 0..40 step 5) {
                val timestamp = start + minute * 60_000L
                store.bgReadings = readings(timestamp, 300_000, jitter = jitter)
                store.createBucketedData(aapsLogger, dateUtil)
                assertEquals(timestamp, store.lastBg()!!.timestamp)
                assertEquals(140.0, store.lastBg()!!.value)
                assertEquals(true, store.lastUsed5minCalculation)
                assertTrue(store.bucketedData!!.zipWithNext().all { (a, b) -> a.timestamp - b.timestamp == 300_000L })
                assertTrue(store.bucketedData!!.all { it.timestamp <= timestamp })
            }
        }
    }

    @Test fun `sensor restart with a new phase does not inherit the previous anchor`() {
        val store = AutosensDataStoreObject()
        store.bgReadings = readings(start, 60_000)
        store.createBucketedData(aapsLogger, dateUtil)
        val restarted = start + 3_721_000L
        store.bgReadings = readings(restarted, 300_000)
        store.createBucketedData(aapsLogger, dateUtil)
        assertEquals(restarted, store.lastBg()!!.timestamp)
        assertEquals(true, store.lastUsed5minCalculation)
        assertEquals(-1L, store.referenceTime)
    }
}

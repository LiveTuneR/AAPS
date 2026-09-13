package app.aaps.plugins.main.iob

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.implementation.iob.AutosensDataObject
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutosensOwnershipStressTest : TestBaseWithProfile() {
    private val start = 1_789_214_477_000L

    @Test fun `history invalidation reset and pruning cannot resurrect invalidated fallback`() {
        val store=AutosensDataStoreObject()
        fun seed() { store.storedLastAutosensResult=AutosensDataObject(aapsLogger,preferences,dateUtil).apply { time=start; cob=20.0 } }
        seed()
        store.newHistoryData(start-1,aapsLogger,dateUtil)
        assertNull(store.storedLastAutosensResult)
        seed()
        store.reset()
        assertNull(store.storedLastAutosensResult)
        seed()
        store.pruneOlderThan(start+1)
        assertNull(store.storedLastAutosensResult)
        seed()
        store.newHistoryData(start+1,aapsLogger,dateUtil)
        assertEquals(20.0,store.storedLastAutosensResult?.cob)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ownedTable(store: AutosensDataStoreObject) = store.javaClass.getDeclaredField("table").apply { isAccessible = true }
        .get(store) as LongSparseArray<AutosensData>

    @Test fun `clone owns rows nested carbs result and fallback even when public getters are snapshots`() {
        val source = AutosensDataStoreObject()
        val row = AutosensDataObject(aapsLogger, preferences, dateUtil).apply {
            time = start
            cob = 21.0
            activeCarbsList.add(AutosensData.CarbsInPast(start, 21.0, 3.0, 21.0))
            extraDeviation.add(2.0)
            autosensResult.ratio = 1.1
        }
        source.putAutosensData(start,row)
        source.storedLastAutosensResult = row
        val clone = source.clone() as AutosensDataStoreObject
        synchronized(source.dataLock) {
            val owned = ownedTable(source)[start]!!
            val copied = ownedTable(clone)[start]!!
            assertNotSame(owned,copied)
            assertNotSame(owned.activeCarbsList,copied.activeCarbsList)
            assertNotSame(owned.activeCarbsList[0],copied.activeCarbsList[0])
            assertNotSame(owned.extraDeviation,copied.extraDeviation)
            assertNotSame(owned.autosensResult,copied.autosensResult)
            owned.cob = -999.0
            owned.activeCarbsList[0].remaining = -999.0
            owned.autosensResult.ratio = 9.0
            assertEquals(21.0,copied.cob)
            assertEquals(21.0,copied.activeCarbsList[0].remaining)
            assertEquals(1.1,copied.autosensResult.ratio)
        }
        clone.autosensDataTable[start]!!.cob = -999.0
        assertEquals(21.0,clone.autosensDataTable[start]!!.cob)
        assertEquals(21.0,clone.storedLastAutosensResult!!.cob)
    }

    @Test fun `four controlled workers run 1000 load clone bucket prune invalidate reset rounds without escaped state`() {
        val store = AutosensDataStoreObject()
        val barrier = CyclicBarrier(4)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0..3).map { worker -> workers.submit {
                repeat(1000) { round ->
                    barrier.await(20,TimeUnit.SECONDS)
                    val now = start + round * 60_000L
                    when (worker) {
                        0 -> synchronized(store.dataLock) {
                            store.bgReadings = (0..30).map { GV(id = it + 1L, timestamp = now - it * 60_000L, value = 110.0 + it,
                                raw = null, noise = null, trendArrow = app.aaps.core.data.model.TrendArrow.FLAT,
                                sourceSensor = app.aaps.core.data.model.SourceSensor.UNKNOWN) }
                            store.createBucketedData(aapsLogger,dateUtil)
                            assertEquals(-1L,store.referenceTime)
                        }
                        1 -> {
                            store.putAutosensData(now, AutosensDataObject(aapsLogger,preferences,dateUtil).apply { time=now; cob=20.0 })
                            val copy = store.clone() as AutosensDataStoreObject
                            assertEquals(-1L,copy.referenceTime)
                            copy.markCalculationCompleted()
                            copy.reset()
                        }
                        2 -> {
                            store.pruneOlderThan(now - 600_000)
                            store.newHistoryData(now,aapsLogger,dateUtil)
                            if (round % 10 == 0) store.reset()
                        }
                        3 -> synchronized(store.dataLock) {
                            store.bgReadings.firstOrNull()?.value = -999.0
                            store.bucketedData?.firstOrNull()?.value = -999.0
                            val snapshot = store.autosensDataTable
                            if (snapshot.size() > 0) snapshot.valueAt(0).cob = -999.0
                            assertTrue(store.bgReadings.none { it.value == -999.0 })
                            assertTrue(store.bucketedData?.none { it.value == -999.0 } ?: true)
                            val second = store.autosensDataTable
                            for (index in 0 until second.size()) assertNotEquals(-999.0,second.valueAt(index).cob)
                        }
                    }
                    barrier.await(20,TimeUnit.SECONDS)
                }
            } }
            tasks.forEach { it.get(90,TimeUnit.SECONDS) }
            assertEquals(-1L,store.referenceTime)
        } finally { workers.shutdownNow() }
    }
}

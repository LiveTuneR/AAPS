package app.aaps.plugins.main.iob.iobCobCalculator

import app.aaps.core.interfaces.aps.IobTotal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoricalIobCacheTest {
    private val now = 1_000_000L
    private fun put(cache: HistoricalIobCache, time: Long, iob: Double = 1.25) {
        assertTrue(cache.store(time, now, cache.lookup(time, now).revision, IobTotal(time, iob, iobWithZeroTemp = IobTotal(time, iob))))
    }

    @Test fun `cold and warm values match and mutable callers cannot corrupt the cache`() {
        val cache = HistoricalIobCache()
        val input = IobTotal(900_000, 1.25, activity = 0.125, iobWithZeroTemp = IobTotal(900_000, 0.75))
        assertNull(cache.lookup(input.time, now).value)
        assertTrue(cache.store(input.time, now, cache.lookup(input.time, now).revision, input))
        assertEquals(input, cache.lookup(input.time, now).value)
        input.iob = 99.0
        input.iobWithZeroTemp!!.iob = 99.0
        val first = cache.lookup(input.time, now).value!!
        assertEquals(1.25, first.iob)
        assertEquals(0.75, first.iobWithZeroTemp!!.iob)
        first.iobWithZeroTemp!!.iob = -99.0
        assertEquals(0.75, cache.lookup(input.time, now).value!!.iobWithZeroTemp!!.iob)
    }

    @Test fun `every dependency reset rejects the in flight value`() {
        for (reason in listOf("insert", "update", "delete", "cancel", "sync", "import", "databaseReset", "profile", "configuration", "timezone", "DST", "clock")) {
            val cache = HistoricalIobCache()
            put(cache, 800_000)
            val old = cache.lookup(900_000, now)
            cache.invalidate(reason)
            assertFalse(cache.store(900_000, now, old.revision, IobTotal(900_000, 2.0)), reason)
            assertNull(cache.lookup(800_000, now).value, reason)
            assertEquals(1L, cache.stats().rejectedWrites)
            assertEquals(1L, cache.stats().invalidations[reason])
        }
    }

    @Test fun `backdated range invalidates boundary and later entries only`() {
        val cache = HistoricalIobCache()
        for (t in listOf(700_000L, 800_000L, 900_000L)) put(cache, t)
        cache.invalidate("range", 800_000)
        assertNotNull(cache.lookup(700_000, now).value)
        assertNull(cache.lookup(800_000, now).value)
        assertNull(cache.lookup(900_000, now).value)
    }

    @Test fun `memory and age bounds hold under minute arrivals and frequent invalidation`() {
        val cache = HistoricalIobCache(maximumEntries = 32, retentionMs = 60_000)
        for (i in 1..10_000) {
            put(cache, now - i, i.toDouble())
            assertTrue(cache.stats().entries <= 32)
            if (i % 100 == 0) cache.invalidate("therapy")
        }
        put(cache, now - 50_000)
        cache.prune(now + 11_000)
        assertNull(cache.lookup(now - 50_000, now + 11_000).value)
    }

    @Test fun `future value is never promoted to past when calculation took time`() {
        val cache = HistoricalIobCache()
        assertTrue(cache.store(now + 1, now, cache.lookup(now + 1, now).revision, IobTotal(now + 1, 3.0)))
        assertNull(cache.lookup(now + 1, now + 1000).value)
        assertEquals(0, cache.stats().entries)
    }
}

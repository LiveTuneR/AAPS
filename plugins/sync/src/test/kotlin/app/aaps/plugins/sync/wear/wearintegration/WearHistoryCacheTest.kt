package app.aaps.plugins.sync.wear.wearintegration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WearHistoryCacheTest {
    @Test fun `identical routine refresh reuses one snapshot but manual and reconnect resend`() {
        val cache = WearHistoryCache<List<Double>, List<Double>>(1_000)
        val key = listOf(100.0, 101.0)
        val first = cache.snapshot(key, 0, false) { key.toList() }
        repeat(100) { assertNull(cache.snapshot(key, it.toLong() + 1, false) { error("rebuilt") }) }
        assertSame(first, cache.snapshot(key, 999, true) { error("rebuilt") })
        cache.reconnect()
        assertSame(first, cache.snapshot(key, 999, false) { error("rebuilt") })
        assertSame(first, cache.snapshot(key, 1_999, false) { error("rebuilt") })
        assertEquals(1L, cache.builds)
        assertEquals(4L, cache.sends)
    }
    @Test fun `backdated glucose correction or unit change sends immediately`() {
        val cache = WearHistoryCache<Pair<List<Double>, String>, String>()
        assertEquals("a", cache.snapshot(listOf(100.0, 101.0) to "mgdl", 0, false) { "a" })
        assertEquals("b", cache.snapshot(listOf(99.0, 101.0) to "mgdl", 1, false) { "b" })
        assertEquals("c", cache.snapshot(listOf(99.0, 101.0) to "mmol", 2, false) { "c" })
        assertEquals(3L, cache.builds)
    }
    @Test fun `coalescing keeps first and latest event under continuous traffic`() {
        val scheduler = io.reactivex.rxjava3.schedulers.TestScheduler()
        val events = io.reactivex.rxjava3.subjects.PublishSubject.create<Int>()
        val observer = events.throttleLatest(500, java.util.concurrent.TimeUnit.MILLISECONDS, scheduler, true).test()
        events.onNext(0)
        for (i in 1..10) {
            scheduler.advanceTimeBy(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            events.onNext(i)
        }
        scheduler.advanceTimeBy(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        events.onComplete()
        observer.assertValues(0, 9, 10).assertComplete()
    }
}

package app.aaps.core.data.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LatestPendingCalculationTest {
    private class Journal : CalculationJournal {
        var state: CalculationQueueState? = null
        var fail = false
        override fun read() = state
        override fun write(state: CalculationQueueState) { check(!fail); this.state = state }
    }

    @Test fun `minute CGM makes bounded progress at 50 70 90 120 seconds without a quiet tail`() {
        for (duration in listOf(50_000L, 70_000L, 90_000L, 120_000L)) {
            val queue = LatestPendingCalculation(Journal())
            var active: ActiveCalculation? = null
            var finishAt = Long.MAX_VALUE
            var inputAt = 0L
            val observed = mutableListOf<Long>()
            val completionTimes = mutableListOf<Long>()
            while (minOf(inputAt, finishAt) <= 30 * 60_000L) {
                val now = minOf(inputAt, finishAt)
                if (now == finishAt) {
                    val run = requireNotNull(active)
                    assertTrue(queue.isCurrent(run.generation))
                    val bg = requireNotNull(run.intent.rawBgTimestamp)
                    assertTrue(queue.claimBg(run.generation, bg))
                    assertFalse(queue.claimBg(run.generation, bg))
                    observed += bg
                    completionTimes += now
                    assertTrue(queue.finish(run.generation, now, true))
                    active = null
                    finishAt = Long.MAX_VALUE
                }
                if (now == inputAt) {
                    queue.offer(CalculationIntent(now, now, rawBgTimestamp = now + 1, newBg = true))
                    inputAt += 60_000
                }
                if (active == null) {
                    active = queue.startNext(now)
                    if (active != null) finishAt = now + duration
                }
                assertNull(queue.startNext(now), "A second owner must never start")
            }
            assertEquals((1_800_000L / maxOf(duration, 60_000L)).toInt(), observed.size)
            assertEquals(observed.size, observed.distinct().size)
            assertTrue(observed.zipWithNext().all { (a,b) -> b > a && b - a <= duration + 60_000 })
            assertTrue(completionTimes.zipWithNext().all { (a,b) -> b - a <= maxOf(duration, 60_000) })
            assertEquals(0L, queue.snapshot().cancelledCount)
            assertTrue((queue.snapshot().pending?.rawBgTimestamp ?: active?.intent?.rawBgTimestamp ?: 0) >= 1_740_001)
        }
    }

    @Test fun `BG carb BG profile BG temp target preserves earliest history and latest glucose`() {
        val queue = LatestPendingCalculation(Journal())
        queue.offer(CalculationIntent(100,100,100,newBg = true))
        val old = queue.startNext(100)!!
        val therapy = listOf(40L, 10L, 30L)
        therapy.forEachIndexed { index, from ->
            queue.invalidate()
            assertFalse(queue.isCurrent(old.generation))
            queue.offer(CalculationIntent(200 + index.toLong(), 200 + index.toLong(), invalidateFrom = from, therapy = true))
            queue.offer(CalculationIntent(300 + index.toLong(), 300 + index.toLong(), 300 + index.toLong(), newBg = true))
        }
        assertFalse(queue.claimBg(old.generation, 100))
        assertNull(queue.startNext(400))
        queue.finish(old.generation,400,true)
        val next = queue.startNext(400)!!
        assertEquals(10L,next.intent.invalidateFrom)
        assertEquals(302L,next.intent.rawBgTimestamp)
        assertTrue(next.intent.therapy)
        assertTrue(next.intent.newBg)
        assertTrue(queue.claimBg(next.generation,302))
    }

    @Test fun `restart retains pending history and claim but never replays a therapy command`() {
        val journal = Journal()
        var queue = LatestPendingCalculation(journal)
        queue.offer(CalculationIntent(100,100,100,newBg = true))
        val first = queue.startNext(100)!!
        assertTrue(queue.claimBg(first.generation,100))
        queue.offer(CalculationIntent(200,200,200,50,newBg=true,therapy=true))
        queue = LatestPendingCalculation(journal)
        assertNull(queue.snapshot().active)
        val recovered = queue.startNext(300)!!
        assertTrue(recovered.intent.recovered)
        assertEquals(50L,recovered.intent.invalidateFrom)
        assertEquals(200L,recovered.intent.rawBgTimestamp)
        assertFalse(queue.claimBg(recovered.generation,100))
        assertTrue(queue.claimBg(recovered.generation,200))
        assertFalse(queue.finish(first.generation,400,true))
    }

    @Test fun `journal write failure never changes acknowledged state or permits duplicate claim`() {
        val journal = Journal()
        val queue = LatestPendingCalculation(journal)
        queue.offer(CalculationIntent(100,100,100,newBg=true))
        val active = queue.startNext(100)!!
        journal.fail = true
        assertThrows(IllegalStateException::class.java) { queue.claimBg(active.generation,100) }
        assertEquals(0L,queue.snapshot().lastClaimedBg)
        assertThrows(IllegalStateException::class.java) { queue.finish(active.generation,200,true) }
        assertNotNull(queue.snapshot().active)
    }

    @Test fun `history barrier prevents a pending BG starting before ordered invalidation is committed`() {
        val queue = LatestPendingCalculation(Journal())
        queue.offer(CalculationIntent(100,100,100,newBg=true))
        val old = queue.startNext(100)!!
        queue.offer(CalculationIntent(200,200,200,newBg=true))
        queue.holdHistory(210,40)
        assertFalse(queue.isCurrent(old.generation))
        queue.finish(old.generation,220,true)
        assertNull(queue.startNext(220))
        queue.offer(CalculationIntent(230,230,230,newBg=true))
        assertNull(queue.startNext(230))
        queue.offer(CalculationIntent(240,240,invalidateFrom=80,therapy=true))
        val next = queue.startNext(240)!!
        assertEquals(40L,next.intent.invalidateFrom)
        assertEquals(230L,next.intent.rawBgTimestamp)
    }

    @Test fun `ordered therapy decision on same BG is once only and cannot replay after restart`() {
        val journal = Journal()
        var queue = LatestPendingCalculation(journal)
        queue.offer(CalculationIntent(100,100,100,newBg=true))
        val bg = queue.startNext(100)!!
        assertTrue(queue.claimBg(bg.generation,100))
        queue.finish(bg.generation,150,true)
        queue.offer(CalculationIntent(160,160,invalidateFrom=100,therapy=true))
        val therapy = queue.startNext(160)!!
        assertTrue(queue.claimBg(therapy.generation,100,therapy=true))
        assertFalse(queue.claimBg(therapy.generation,100,therapy=true))
        queue = LatestPendingCalculation(journal)
        val recovery = queue.startNext(200)!!
        assertFalse(queue.claimBg(recovery.generation,100,therapy=true))
    }
}

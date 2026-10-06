package app.aaps.workflow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LatestPendingGraphTest {
    @Test fun `minute CGM and 90 second graphs keep one active and latest pending without cancelling mandatory work`() {
        val queue = LatestPendingGraph<Int, Int>()
        var active = queue.offer(0, 0)!!
        var finishAt = 90
        var mandatory = 1
        val publications = mutableListOf<Int>()
        var newest = 0
        for (second in 1..3600) {
            if (second % 60 == 0) {
                newest++; mandatory++
                assertNull(queue.offer(newest, newest))
            }
            if (second == finishAt) {
                val valid = active.key == newest
                if (valid) publications.add(active.key)
                queue.finish(active.key, valid)?.let { active = it; finishAt += 90 }
            }
            val (running, pending) = queue.occupancy()
            assertTrue(running <= 1 && pending <= 1)
        }
        assertEquals(61, mandatory)
        assertTrue(queue.started < mandatory * 0.75)
        assertTrue(queue.coalesced > 0)
        assertTrue(publications.isEmpty()) // stale work finishes but never paints an old generation
        assertEquals(40L, queue.completed)
    }
    @Test fun `duplicate requests and stale completion cannot displace latest pending`() {
        val queue = LatestPendingGraph<Long, String>()
        assertEquals("a", queue.offer(1, "a")!!.value)
        queue.offer(2, "b"); queue.offer(3, "c")
        assertNull(queue.finish(9, false))
        assertEquals("c", queue.finish(1, false)!!.value)
        assertNull(queue.offer(3, "duplicate"))
        assertNull(queue.finish(3, true)); assertEquals(0 to 0, queue.occupancy())
    }
}

package app.aaps.implementation.telemetry

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DemandDiagnosticFlushTest {
    private class Clock {
        var now = 0L
        val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        fun schedule(delay: Long, action: () -> Unit): AutoCloseable {
            val cancelled = booleanArrayOf(false)
            tasks.add(Triple(now + delay, action, cancelled))
            return AutoCloseable { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.first <= now }.toList()
            tasks.removeAll(due.toSet())
            due.filter { !it.third[0] }.forEach { it.second() }
        }
    }

    @Test fun `ten idle minutes schedule nothing and burst owns one deadline`() {
        val clock = Clock(); var flushes = 0
        val scheduler = DemandDiagnosticFlush(clock::schedule) { flushes++ }
        clock.advance(600_000)
        assertEquals(0, flushes); assertTrue(clock.tasks.isEmpty())
        repeat(100) { scheduler.pendingRecord() }
        assertEquals(1, clock.tasks.size)
        clock.advance(999); assertEquals(0, flushes)
        clock.advance(1); assertEquals(1, flushes)
        scheduler.pendingRecord() // writer has not drained yet
        assertTrue(clock.tasks.isEmpty())
        scheduler.drained(); clock.advance(600_000)
        assertEquals(1, flushes)
    }

    @Test fun `pressure critical and export drains invalidate stale callback`() {
        val clock = Clock(); var flushes = 0
        val scheduler = DemandDiagnosticFlush(clock::schedule) { flushes++ }
        scheduler.pendingRecord(); val stale = clock.tasks.single().second
        scheduler.drained(); scheduler.pendingRecord()
        stale(); assertEquals(0, flushes)
        clock.advance(1000); assertEquals(1, flushes)
        scheduler.drained(); clock.advance(600_000); assertEquals(1, flushes)
    }

    @Test fun `shutdown cancels deadline and rejects later records`() {
        val clock = Clock(); var flushes = 0
        val scheduler = DemandDiagnosticFlush(clock::schedule) { flushes++ }
        scheduler.pendingRecord(); val callback = clock.tasks.single().second
        scheduler.close(); callback(); scheduler.pendingRecord(); clock.advance(600_000)
        assertEquals(0, flushes); assertTrue(clock.tasks.isEmpty())
    }

    @Test fun `concurrent record and drain always retain a deadline for new records`() {
        val clock = Clock(); var flushes = 0
        val scheduler = DemandDiagnosticFlush(clock::schedule) { flushes++ }
        repeat(1000) {
            scheduler.pendingRecord()
            scheduler.drained()
            scheduler.pendingRecord()
            clock.advance(1000)
            scheduler.drained()
        }
        assertEquals(1000, flushes)
    }
}

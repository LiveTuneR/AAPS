package app.aaps.core.data.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoundedDiagnosticTraceTest {
    @Test fun `saturation retains latest detail and reports overwritten records`() {
        val ring = BoundedDiagnosticTrace(3, 100)
        for (i in 0..999) ring.offer("event:$i")
        val snapshot = ring.drain()
        assertEquals(listOf("event:997", "event:998", "event:999"), snapshot.lines)
        assertEquals(997L, snapshot.dropped)
        assertTrue(snapshot.bytes <= 100)
        assertTrue(ring.drain().lines.isEmpty())
    }
    @Test fun `byte bound counts utf8 and oversized records are reported`() {
        val ring = BoundedDiagnosticTrace(512, 8)
        ring.offer("жжж")
        ring.offer("яя")
        ring.offer("123456789")
        val snapshot = ring.drain()
        assertEquals(listOf("яя"), snapshot.lines)
        assertEquals(2L, snapshot.dropped)
        assertEquals(4, snapshot.bytes)
    }
}

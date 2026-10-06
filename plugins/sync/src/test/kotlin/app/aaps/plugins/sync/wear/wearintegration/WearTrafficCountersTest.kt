package app.aaps.plugins.sync.wear.wearintegration

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WearTrafficCountersTest {
    @Test fun `utf8 payload bytes and acknowledgements are separate and monotonic`() {
        var mono = 0L
        val counters = WearTrafficCounters { mono }
        val payload = "данные".toByteArray(Charsets.UTF_8)
        counters.sent(payload.size); counters.completed(true)
        counters.sent(25); counters.completed(false)
        assertNull(counters.reportIfDue())
        mono = 60_000_000_000
        val stats = counters.reportIfDue()!!
        assertEquals(2L,stats.messages); assertEquals(payload.size.toLong()+25,stats.bytes)
        assertEquals(1L,stats.succeeded); assertEquals(1L,stats.failed); assertEquals(60_000L,stats.durationMs)
        assertNull(counters.reportIfDue())
    }
}

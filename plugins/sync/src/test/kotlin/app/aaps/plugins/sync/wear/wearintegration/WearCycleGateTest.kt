package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WearCycleGateTest {
    @Test fun `failed build is retryable and each logical cycle commits one terminal state`() {
        val gate = WearCycleGate()
        assertTrue(gate.needsBuild(7))
        assertTrue(gate.needsBuild(7)) // failure before commit
        gate.committed(7)
        repeat(100) { assertFalse(gate.needsBuild(7)) }
        assertTrue(gate.needsBuild(8)); assertTrue(gate.needsBuild(null)) // explicit request
        gate.reset(); assertTrue(gate.needsBuild(7))
    }
    @Test fun `early BG after final status cannot resend unchanged early frame`() {
        val gate = WearDomainDelivery()
        val bg = EventData.SingleBg(0, 1000, sgv = 100.0, high = 180.0, low = 70.0)
        assertTrue(gate.accept(EventData.FastStatus(bg, null)))
        assertTrue(gate.accept(EventData.FastStatus(bg, null, arrayListOf())))
        assertFalse(gate.accept(EventData.FastStatus(bg.copy(), null)))
        assertEquals(2L, gate.stats().sends.values.sum())
    }
    @Test fun `default history has no five minute or daily heartbeat`() {
        val cache = WearHistoryCache<String, String>()
        assertEquals("history", cache.snapshot("key", 0, false) { "history" })
        for (time in listOf(300_000_000_000L, 86_400_000_000_000L))
            assertNull(cache.snapshot("key", time, false) { fail("unchanged history rebuilt") })
        cache.reconnect()
        assertEquals("history", cache.snapshot("key", 86_400_000_000_000L, false) { fail("reconnect rebuilt unchanged data") })
    }
}

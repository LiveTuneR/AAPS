package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WearDomainDeliveryTest {
    @Test fun `same timestamp correction is visible and unchanged content does not need serialization`() {
        val gate = WearDomainDelivery()
        val bg = EventData.SingleBg(0, 1000, sgv = 100.0, high = 180.0, low = 70.0)
        assertTrue(gate.accept(EventData.FastStatus(bg, null)))
        repeat(100) { assertFalse(gate.accept(EventData.FastStatus(bg.copy(), null))) }
        assertTrue(gate.accept(EventData.FastStatus(bg.copy(sgv = 110.0), null)))
        assertTrue(gate.accept(EventData.FastStatus(bg.copy(delta = "+1"), null)))
        assertEquals(100L, gate.stats().skippedUnchanged)
    }
    @Test fun `domain changes are independent and reconnect forces full snapshot`() {
        val gate = WearDomainDelivery()
        val actions = EventData.UserAction(arrayListOf(EventData.UserAction.UserActionEntry(1, "id", "title")))
        assertTrue(gate.accept(actions))
        assertFalse(gate.accept(actions.copy(entries = arrayListOf(actions.entries.single().copy(timeStamp = 10_000)))))
        assertTrue(gate.accept(EventData.ActiveSceneState(true)))
        assertFalse(gate.accept(actions))
        gate.reset(fullResync = true); assertTrue(gate.accept(actions)); assertEquals(1L, gate.stats().fullResyncs)
    }
    @Test fun `urgent progress and interactive mode replies bypass routine suppression`() {
        val gate = WearDomainDelivery()
        repeat(100) { assertTrue(gate.accept(EventData.BolusProgress(50, "deliver"))) }
        val modes = EventData.RunningModeList(1, emptyList())
        assertTrue(gate.accept(modes)); assertFalse(gate.accept(modes.copy(timeStamp = 2)))
        assertTrue(gate.accept(modes, force = true))
    }
    @Test fun `minute BG frames leave settings and treatment domains untouched and roundtrip wire schema`() {
        val gate = WearDomainDelivery()
        repeat(60) { minute -> assertTrue(gate.accept(EventData.FastStatus(
            EventData.SingleBg(0, minute * 60_000L, sgv = 100.0, high = 180.0, low = 70.0), null))) }
        assertEquals(mapOf("FAST_BG" to 60L), gate.stats().sends)
        assertTrue(EventData.deserialize(EventData.FastStatus(null, null, arrayListOf()).serialize()) is EventData.FastStatus)
    }
}

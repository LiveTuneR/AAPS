package app.aaps.core.data.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnergyRuntimeCountersTest {
    @Test fun `observations are cumulative and latency samples bounded without polling`() {
        val before = EnergyRuntimeCounters.snapshot()
        val countersBefore = before["counters"] as Map<*, *>
        val old = countersBefore["test.energy.events"] as Long? ?: 0
        repeat(4096) { EnergyRuntimeCounters.add("test.energy.events"); EnergyRuntimeCounters.duration("test.energy.latency", it.toLong()) }
        val snapshot = EnergyRuntimeCounters.snapshot()
        assertEquals(old + 4096, (snapshot["counters"] as Map<*, *>)["test.energy.events"])
        val duration = (snapshot["durations"] as Map<*, *>)["test.energy.latency"] as Map<*, *>
        assertEquals(2048, duration["windowSamples"])
        assertEquals(4095L, duration["maxMs"])
        assertEquals(3992L, duration["p95Ms"])
    }
}

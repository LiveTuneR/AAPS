package app.aaps.implementation.telemetry

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneId

class DoubleDiagnosticBufferTest {
    private fun record(id: Long) = DiagnosticRecord("ACTIVITY", JSONObject().put("id", id), null, null, id, id, ZoneId.of("UTC"))
    @Test fun `boundary swaps and retries without losing accepted records and overload is explicit`() {
        val buffer = DoubleDiagnosticBuffer(2, 8192)
        val accepted = mutableListOf<Long>()
        repeat(10) { if (buffer.offer(record(it.toLong())).accepted) accepted.add(it.toLong()) }
        assertEquals(listOf(0L, 1L, 2L, 3L), accepted)
        assertEquals(accepted, buffer.drain().map { it.utc })
        val loss = buffer.takeLoss()!!
        assertEquals(6L, loss.count); assertEquals(4L, loss.firstUtc); assertEquals(9L, loss.lastUtc)
        assertEquals(mapOf("ACTIVITY" to 6L), loss.types)
        buffer.restoreLoss(loss); assertEquals(loss, buffer.takeLoss())
        assertFalse(buffer.hasRecords())
    }
    @Test fun `producer writer race preserves all accepted observations within two bounded buffers`() {
        val buffer = DoubleDiagnosticBuffer(4, 8192)
        val received = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val accepted = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val producer = Thread { repeat(10_000) { if (buffer.offer(record(it.toLong())).accepted) accepted.add(it.toLong()) } }
        producer.start()
        while (producer.isAlive) received.addAll(buffer.drain().map { it.utc })
        producer.join(); received.addAll(buffer.drain().map { it.utc })
        assertEquals(accepted.toList().sorted(), received.toList().sorted())
        assertEquals(10_000L, accepted.size.toLong() + (buffer.takeLoss()?.count ?: 0))
    }
}

package app.aaps.implementation.telemetry

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

class DiagnosticBatchBufferTest {
    @TempDir lateinit var directory: File
    private fun state(time: Long, value: Int = 1, generation: Long = 7) = DiagnosticRecord("PUMP_STATE",
        JSONObject().put("connected", true).put("reservoir", value), generation, null, time, time * 1_000_000, ZoneId.of("UTC"))

    @Test fun `first last and duration survive identical snapshots with reordered keys`() {
        val buffer = DiagnosticBatchBuffer()
        buffer.offer(state(1000))
        buffer.offer(state(1100).copy(data = JSONObject().put("reservoir", 1).put("connected", true)))
        buffer.offer(state(1200))
        val records = buffer.drain()
        assertEquals(1, records.size)
        assertEquals(1000L, records.single().utc)
        val aggregation = records.single().data.getJSONObject("diagnosticAggregation")
        assertEquals(1000L, aggregation.getLong("firstSeenUtc"))
        assertEquals(1200L, aggregation.getLong("lastSeenUtc"))
        assertEquals(3L, aggregation.getLong("repeatCount"))
    }

    @Test fun `transitions generations and heartbeat boundaries cannot be merged`() {
        val buffer = DiagnosticBatchBuffer()
        for (record in listOf(state(0), state(100, 2), state(200, 2, 8), state(1200, 2, 8))) assertTrue(buffer.offer(record))
        assertEquals(4, buffer.drain().size)
    }

    @Test fun `continuous traffic has both record and byte bounds and backpressure counts`() {
        val buffer = DiagnosticBatchBuffer(maxRecords = 4, maxBytes = 4096)
        repeat(10_000) { buffer.offer(state(it.toLong(), it)) }
        assertTrue(buffer.stats().records <= 4)
        assertTrue(buffer.stats().bufferedBytes <= 4096)
        assertTrue(buffer.stats().rejected > 9990)
        assertTrue(buffer.drain().isNotEmpty())
        assertEquals(0, buffer.stats().bufferedBytes)
    }

    @Test fun `synthetic repeated diagnostic burst reduces actual jsonl bytes and sync calls`() {
        val oldDirectory = File(directory, "old")
        val newDirectory = File(directory, "new")
        val old = TherapyTelemetryStore(oldDirectory, "test", wallClock = { 1000 }, monotonic = { 1 })
        val optimized = TherapyTelemetryStore(newDirectory, "test", wallClock = { 1000 }, monotonic = { 1 })
        val buffer = DiagnosticBatchBuffer()
        repeat(100) {
            val record = state(1000L + it)
            old.append(record.type, record.data, observedUtc = record.utc, observedMonotonic = record.monotonic, observedZone = record.zone)
            assertTrue(buffer.offer(record))
        }
        for (record in buffer.drain()) optimized.append(record.type, record.data, observedUtc = record.utc,
            observedMonotonic = record.monotonic, observedZone = record.zone, durable = false)
        optimized.flush()
        assertTrue(File(newDirectory, "active.jsonl").length() * 3 < File(oldDirectory, "active.jsonl").length())
        assertTrue(optimized.ioStats().durableSyncs * 3 < old.ioStats().durableSyncs)
        File("build/reports/energy-fixtures").also { it.mkdirs() }.resolve("diagnostic-burst.json").writeText(
            JSONObject().put("source","SYNTHETIC_REPEATED_STATE_BURST").put("observations",100)
                .put("oldJsonlBytes",File(oldDirectory,"active.jsonl").length())
                .put("newJsonlBytes",File(newDirectory,"active.jsonl").length())
                .put("oldStoreSyncs",old.ioStats().durableSyncs).put("newStoreSyncs",optimized.ioStats().durableSyncs)
                .put("oldAppends",old.ioStats().appendCalls).put("newAppends",optimized.ioStats().appendCalls)
                .put("scope","store only; admission WAL syncs separate").put("batteryMeasured",false).toString(2))
        assertEquals(optimized.reconcileDiskBytes(), optimized.bytesOnDisk())
        old.close(); optimized.close()
    }

    @Test fun `batch admission survives interruption and old ledger rows still load`() {
        val file = File(directory, "admission.jsonl")
        val ledger = TelemetryAdmissionLedger(file)
        ledger.admit(1000, "PUMP_SENT")
        ledger.admit(1100, "DIAGNOSTIC_BATCH", 128, 2100)
        val loss = TelemetryAdmissionLedger(file).pendingLoss()!!
        assertEquals(129L, loss.count)
        assertEquals(1000L, loss.firstUtc)
        assertEquals(2100L, loss.lastUtc)
        file.writeText("""{"kind":"accepted","sequence":1,"timestampUtc":500,"type":"PUMP_SENT"}
""")
        assertEquals(1L, TelemetryAdmissionLedger(file).pendingLoss()!!.count)
    }
}

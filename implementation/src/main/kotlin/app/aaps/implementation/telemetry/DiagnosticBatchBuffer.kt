package app.aaps.implementation.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

internal data class DiagnosticRecord(
    val type: String, val data: JSONObject, val generation: Long?, val correlationId: String?,
    val utc: Long, val monotonic: Long, val zone: ZoneId,
)

/** Observation only; command/decision events must never enter this buffer. */
internal class DiagnosticBatchBuffer(private val maxRecords: Int = 128, private val maxBytes: Int = 64 * 1024) {
    init { require(maxRecords > 0 && maxBytes > 0) }
    private data class Entry(val first: DiagnosticRecord, val key: String, val bytes: Int, var last: DiagnosticRecord, var repeats: Long = 1)
    private val entries = ArrayList<Entry>()
    private var bytes = 0
    private var offeredBytes = 0L
    private var repeats = 0L
    private var rejected = 0L
    data class Stats(val records: Int, val bufferedBytes: Int, val offeredBytes: Long, val repeats: Long, val rejected: Long)

    @Synchronized fun offer(record: DiagnosticRecord): Boolean {
        val key = canonical(record.data)
        val size = key.toByteArray(Charsets.UTF_8).size + 1024 // envelope + aggregation overhead
        offeredBytes += size
        val previous = entries.lastOrNull()
        if (record.type == "PUMP_STATE" && previous != null && previous.first.type == record.type && previous.key == key &&
            previous.first.generation == record.generation && previous.first.correlationId == record.correlationId &&
            previous.first.zone == record.zone && record.monotonic >= previous.last.monotonic &&
            record.monotonic - previous.first.monotonic < MAX_AGE_NANOS) {
            previous.last = record
            previous.repeats++
            repeats++
            return true
        }
        if (entries.size >= maxRecords || bytes + size > maxBytes) { rejected++; return false }
        entries.add(Entry(record, key, size, record))
        bytes += size
        return true
    }

    @Synchronized fun drain(): List<DiagnosticRecord> {
        val result = entries.map { entry ->
            val data = JSONObject(entry.first.data.toString())
            if (entry.repeats > 1) data.put("diagnosticAggregation", JSONObject()
                .put("firstSeenUtc", entry.first.utc).put("lastSeenUtc", entry.last.utc).put("repeatCount", entry.repeats)
                .put("lastMonotonicNanos", entry.last.monotonic))
            entry.first.copy(data = data)
        }
        entries.clear(); bytes = 0
        return result
    }

    @Synchronized fun stats() = Stats(entries.size, bytes, offeredBytes, repeats, rejected)
    companion object {
        const val MAX_AGE_NANOS = 1_000_000_000L
        // Key order has no meaning in a state snapshot.
        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            else -> value.toString()
        }
    }
}

package app.aaps.implementation.telemetry

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal data class TelemetryAdmission(val sequence: Long, val timestampUtc: Long, val type: String)
internal data class TelemetryAdmissionLoss(val firstUtc: Long, val lastUtc: Long, val count: Long, val throughSequence: Long)

/** Durable acceptance/commit WAL. An unclean process leaves accepted entries without commits. */
internal class TelemetryAdmissionLedger(private val file: File) {
    private val lock = Any()
    private var nextSequence = 1L
    private val accepted = sortedMapOf<Long, TelemetryAdmission>()
    private val committed = mutableSetOf<Long>()

    init { load() }

    fun admit(timestampUtc: Long, type: String): TelemetryAdmission = synchronized(lock) {
        val admission = TelemetryAdmission(nextSequence++, timestampUtc, type)
        append(JSONObject().put("kind", "accepted").put("sequence", admission.sequence).put("timestampUtc", timestampUtc).put("type", type))
        accepted[admission.sequence] = admission
        admission
    }

    fun commit(sequence: Long) = synchronized(lock) {
        append(JSONObject().put("kind", "committed").put("sequence", sequence).put("timestampUtc", System.currentTimeMillis()))
        committed += sequence
        accepted.remove(sequence)
        compactIfSettled()
    }

    fun pendingLoss(): TelemetryAdmissionLoss? = synchronized(lock) {
        val pending = accepted.values
        if (pending.isEmpty()) null else TelemetryAdmissionLoss(
            firstUtc = pending.minOf(TelemetryAdmission::timestampUtc),
            lastUtc = pending.maxOf(TelemetryAdmission::timestampUtc),
            count = pending.size.toLong(),
            throughSequence = pending.maxOf(TelemetryAdmission::sequence),
        )
    }

    fun acknowledgeLoss(throughSequence: Long) = synchronized(lock) {
        accepted.keys.filter { it <= throughSequence }.toList().forEach(::commit)
    }

    private fun load(): Unit = synchronized(lock) {
        if (!file.exists()) return@synchronized
        file.useLines { lines -> lines.forEach { line ->
            val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val sequence = row.optLong("sequence")
            nextSequence = maxOf(nextSequence, sequence + 1, row.optLong("nextSequence", 1L))
            when (row.optString("kind")) {
                "accepted" -> accepted[sequence] = TelemetryAdmission(sequence, row.optLong("timestampUtc"), row.optString("type"))
                "committed" -> committed += sequence
            }
        } }
        accepted.keys.removeAll(committed)
        Unit
    }

    private fun append(row: JSONObject) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write((row.toString() + "\n").toByteArray())
            output.flush()
            output.fd.sync()
        }
    }

    private fun compactIfSettled() {
        if (accepted.isNotEmpty() || file.length() < 1024 * 1024) return
        val replacement = File(file.parentFile, "${file.name}.new")
        FileOutputStream(replacement).use { output ->
            output.write((JSONObject().put("kind", "checkpoint").put("nextSequence", nextSequence).put("timestampUtc", System.currentTimeMillis()).toString() + "\n").toByteArray())
            output.fd.sync()
        }
        if (!replacement.renameTo(file)) {
            file.delete()
            check(replacement.renameTo(file))
        }
        committed.clear()
    }
}

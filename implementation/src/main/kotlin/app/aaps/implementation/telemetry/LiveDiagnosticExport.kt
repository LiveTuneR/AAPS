package app.aaps.implementation.telemetry

import app.aaps.core.data.diagnostics.BoundedDiagnosticTrace
import org.json.JSONObject
import java.io.File

/** Explicit support export only. Snapshot neither drains the ring nor records raw frames continuously. */
internal object LiveDiagnosticExport {
    fun write(directory: File, snapshots: Map<String, BoundedDiagnosticTrace.Snapshot>): List<File> {
        directory.mkdirs()
        return snapshots.map { (driver, snapshot) ->
            require(driver in setOf("apex", "medtrum"))
            File(directory, "$driver-live-ring.jsonl").apply {
                bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(JSONObject().put("event","diagnostic_ring_snapshot").put("driver",driver)
                        .put("records",snapshot.lines.size).put("dropped",snapshot.dropped).put("bytes",snapshot.bytes)
                        .put("oldestUtc",snapshot.oldestUtc ?: JSONObject.NULL).put("newestUtc",snapshot.newestUtc ?: JSONObject.NULL)
                        .put("coverage","bounded current process RAM; earlier overwritten or pre-crash frames unavailable").toString())
                    snapshot.lines.forEach(writer::appendLine)
                }
            }
        }
    }
}

package app.aaps.implementation.telemetry

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream

/** Bounded external merge sort: clocks may jump and segment names are never chronological evidence. */
internal object TelemetryExportOrder {
    private val order = compareBy<JSONObject>({ it.getLong("timestampUtc") }, { it.getString("sessionId") }, { it.getLong("sequence") })

    fun forEachOrdered(files: List<File>, scratch: File, corrupt: (File) -> Unit, consume: (JSONObject) -> Unit) {
        val owned = mutableListOf<File>()
        var serial = 0
        fun newRun() = File(scratch,"sort-${serial++}.tmp").also { owned.add(it) }
        try {
            var runs = mutableListOf<File>()
            val chunk = ArrayList<JSONObject>()
            var bytes = 0
            fun flush() {
                if (chunk.isEmpty()) return
                val run = newRun()
                run.bufferedWriter().use { writer -> chunk.sortedWith(order).forEach { writer.appendLine(it.toString()) } }
                runs.add(run); chunk.clear(); bytes=0
            }
            files.forEach { file ->
                try {
                    val input = if (file.name.endsWith(".gz")) GZIPInputStream(file.inputStream()) else file.inputStream()
                    input.bufferedReader().useLines { lines -> lines.forEach { line ->
                        val record = try { JSONObject(line).also {
                            it.getLong("timestampUtc"); it.getString("sessionId"); it.getLong("sequence")
                            it.getString("eventId"); it.getString("type"); it.getJSONObject("data")
                        } } catch (_: Exception) { corrupt(file); null }
                        if (record != null) { chunk.add(record); bytes+=line.length*2; if (bytes>=4*1024*1024) flush() }
                    } }
                } catch (_: java.io.IOException) { corrupt(file) }
            }
            flush()
            while (runs.size > 32) {
                runs = runs.chunked(32).map { group ->
                    val output = newRun()
                    output.bufferedWriter().use { writer -> merge(group) { writer.appendLine(it.toString()) } }
                    group.forEach { check(it.delete()) }
                    output
                }.toMutableList()
            }
            merge(runs,consume)
        } finally { owned.filter { it.exists() }.forEach { check(it.delete()) } }
    }

    private fun merge(files: List<File>, consume: (JSONObject) -> Unit) {
        data class Head(val record: JSONObject, val reader: BufferedReader)
        val readers=mutableListOf<BufferedReader>()
        val heap=PriorityQueue<Head> { a,b -> order.compare(a.record,b.record) }
        try {
            files.forEach { file ->
                val reader=file.bufferedReader().also { readers.add(it) }
                reader.readLine()?.let { heap.add(Head(JSONObject(it),reader)) }
            }
            while (heap.isNotEmpty()) {
                val head=heap.remove()
                consume(head.record)
                head.reader.readLine()?.let { heap.add(Head(JSONObject(it),head.reader)) }
            }
        } finally { readers.forEach { it.close() } }
    }
}

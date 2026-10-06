package app.aaps.core.data.diagnostics

/** Raw transport detail is observational, bounded in RAM, and persisted only on explicit export. */
class BoundedDiagnosticTrace(private val maxRecords: Int = 512, private val maxBytes: Int = 512 * 1024) {
    private data class Entry(val line: String, val size: Int, val utc: Long)
    private val records = ArrayDeque<Entry>()
    private var bytes = 0
    private var dropped = 0L
    data class Snapshot(val lines: List<String>, val dropped: Long, val bytes: Int, val oldestUtc: Long?, val newestUtc: Long?)
    init { require(maxRecords > 0 && maxBytes > 0) }
    @Synchronized fun offer(line: String, utc: Long = System.currentTimeMillis()) {
        val size = line.toByteArray(Charsets.UTF_8).size
        if (size > minOf(maxBytes, 8192)) { dropped++; return }
        while (records.size >= maxRecords || bytes + size > maxBytes) {
            bytes -= records.removeFirst().size; dropped++
        }
        records.addLast(Entry(line, size, utc)); bytes += size
    }
    @Synchronized fun snapshot(): Snapshot = Snapshot(records.map { it.line }, dropped, bytes, records.firstOrNull()?.utc, records.lastOrNull()?.utc)
    @Synchronized fun drain(): Snapshot {
        val result = snapshot()
        records.clear(); bytes = 0; dropped = 0
        return result
    }
}

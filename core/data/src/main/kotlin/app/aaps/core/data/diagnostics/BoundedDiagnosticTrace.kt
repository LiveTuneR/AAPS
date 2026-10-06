package app.aaps.core.data.diagnostics

/** Raw transport detail is observational, bounded in RAM, and persisted only on explicit export. */
class BoundedDiagnosticTrace(private val maxRecords: Int = 512, private val maxBytes: Int = 512 * 1024) {
    private val records = ArrayDeque<Pair<String, Int>>()
    private var bytes = 0
    private var dropped = 0L
    data class Snapshot(val lines: List<String>, val dropped: Long, val bytes: Int)
    init { require(maxRecords > 0 && maxBytes > 0) }
    @Synchronized fun offer(line: String) {
        val size = line.toByteArray(Charsets.UTF_8).size
        if (size > minOf(maxBytes, 8192)) { dropped++; return }
        while (records.size >= maxRecords || bytes + size > maxBytes) {
            bytes -= records.removeFirst().second; dropped++
        }
        records.addLast(line to size); bytes += size
    }
    @Synchronized fun drain(): Snapshot {
        val result = Snapshot(records.map { it.first }, dropped, bytes)
        records.clear(); bytes = 0; dropped = 0
        return result
    }
}

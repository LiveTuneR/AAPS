package app.aaps.core.data.diagnostics

/** Process-local bounded observations. No threads, timers, disk writes or therapy decisions. */
object EnergyRuntimeCounters {
    private val started = System.nanoTime()
    private val counters = linkedMapOf<String, Long>()
    private val durations = linkedMapOf<String, Window>()
    private class Window {
        val values = LongArray(2048)
        var count = 0
        var cursor = 0
        var totalCount = 0L
        var totalMs = 0L
        var maxMs = 0L
        fun add(ms: Long) {
            values[cursor] = ms; cursor = (cursor + 1) % values.size
            count = minOf(count + 1, values.size); totalCount++; totalMs += ms; maxMs = maxOf(maxMs, ms)
        }
        fun snapshot(): Map<String, Any> {
            val sorted = values.copyOf(count).sortedArray()
            fun percentile(p: Double) = if (count == 0) 0L else sorted[((count - 1) * p).toInt()]
            return mapOf("count" to totalCount, "totalMs" to totalMs, "maxMs" to maxMs,
                "windowSamples" to count, "p50Ms" to percentile(.50), "p95Ms" to percentile(.95), "p99Ms" to percentile(.99))
        }
    }
    @Synchronized fun add(key: String, value: Long = 1) {
        if (key in counters || counters.size < 256) counters[key] = (counters[key] ?: 0) + value
    }
    @Synchronized fun duration(key: String, ms: Long) {
        if (key in durations || durations.size < 32) durations.getOrPut(key) { Window() }.add(ms.coerceAtLeast(0))
    }
    @Synchronized fun snapshot(): Map<String, Any> = mapOf(
        "elapsedMs" to (System.nanoTime() - started) / 1_000_000,
        "counters" to counters.toMap(), "durations" to durations.mapValues { it.value.snapshot() },
        "coverage" to "process lifetime; duration percentiles use the latest 2048 samples; CPU from Perfetto, not coroutine thread time")
}

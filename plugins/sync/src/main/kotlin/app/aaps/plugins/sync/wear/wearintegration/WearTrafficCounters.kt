package app.aaps.plugins.sync.wear.wearintegration

/** Counts API send attempts/acknowledgements, not radio energy or proof of watch rendering. */
internal class WearTrafficCounters(private val now: () -> Long = System::nanoTime) {
    private var reportAt = now()
    private var messages = 0L
    private var bytes = 0L
    private var succeeded = 0L
    private var failed = 0L
    data class Snapshot(val messages: Long, val bytes: Long, val succeeded: Long, val failed: Long, val durationMs: Long)
    @Synchronized fun sent(size: Int) { messages++; bytes += size }
    @Synchronized fun completed(success: Boolean) { if (success) succeeded++ else failed++ }
    @Synchronized fun reportIfDue(): Snapshot? {
        val time = now()
        if (time - reportAt < 60_000_000_000L) return null
        val result = Snapshot(messages, bytes, succeeded, failed, (time-reportAt)/1_000_000)
        reportAt = time
        return result
    }
}

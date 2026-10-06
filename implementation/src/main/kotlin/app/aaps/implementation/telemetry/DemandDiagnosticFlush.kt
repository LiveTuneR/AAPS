package app.aaps.implementation.telemetry

/** No idle ticks. A cancelled callback cannot flush a newer batch. */
internal class DemandDiagnosticFlush(
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val requestFlush: () -> Unit,
) : AutoCloseable {
    private var token: Any? = null
    private var timer: AutoCloseable? = null
    private var closed = false
    var callbacks = 0L
        private set

    @Synchronized fun pendingRecord() {
        if (closed || token != null) return
        val current = Any()
        token = current
        timer = schedule(1000L) {
            synchronized(this) {
                if (!closed && token === current) {
                    callbacks++
                    // Keep ownership until the writer drains, including its queueing delay.
                    requestFlush()
                }
            }
        }
    }

    @Synchronized fun drained() {
        token = null
        timer?.close()
        timer = null
    }

    @Synchronized override fun close() {
        closed = true
        drained()
    }
}

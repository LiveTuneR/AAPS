package app.aaps.plugins.sync.nsclientV3.services

/** Independently owned operation leases; idle sockets never own a CPU lease. */
internal class NetworkWakeScope(private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val acquire: (Long) -> AutoCloseable) : AutoCloseable {
    private val active = mutableSetOf<AutoCloseable>()
    private var closed = false
    private var count = 0L
    private var totalMs = 0L
    private var maxMs = 0L
    private var timedOut = 0L
    data class Stats(val count: Long, val active: Int, val totalMs: Long, val maxMs: Long, val timedOut: Long)
    @Synchronized fun stats() = Stats(count, active.size, totalMs, maxMs, timedOut)
    fun lease(): AutoCloseable = leaseOrNull() ?: AutoCloseable { }
    @Synchronized fun leaseOrNull(): AutoCloseable? {
        if (closed) return null
        val underlying = acquire(30_000L)
        val started = now()
        count++
        app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add("wake.ns3.leases")
        lateinit var lease: AutoCloseable
        lease = AutoCloseable { synchronized(this) { if (active.remove(lease)) {
            val duration = (now() - started).coerceAtLeast(0)
            totalMs += duration; maxMs = maxOf(maxMs, duration)
            if (duration >= 30_000L) timedOut++
            app.aaps.core.data.diagnostics.EnergyRuntimeCounters.duration("wake.ns3", duration.coerceAtMost(30_000))
            if (duration >= 30_000L) app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add("wake.ns3.timeouts")
            underlying.close()
        } } }
        active.add(lease)
        return lease
    }
    @Synchronized override fun close() {
        closed = true
        active.toList().forEach { it.close() }
    }
}

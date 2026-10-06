package app.aaps.plugins.sync.nsclientV3.services

/** Independently owned operation leases; idle sockets never own a CPU lease. */
internal class NetworkWakeScope(private val acquire: (Long) -> AutoCloseable) : AutoCloseable {
    private val active = mutableSetOf<AutoCloseable>()
    private var closed = false
    @Synchronized fun lease(): AutoCloseable {
        if (closed) return AutoCloseable { }
        val underlying = acquire(30_000L)
        lateinit var lease: AutoCloseable
        lease = AutoCloseable { synchronized(this) { if (active.remove(lease)) underlying.close() } }
        active.add(lease)
        return lease
    }
    @Synchronized override fun close() {
        closed = true
        active.toList().forEach { it.close() }
    }
}

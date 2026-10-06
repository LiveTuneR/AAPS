package app.aaps.plugins.sync.wear.wearintegration

/** One immutable snapshot. Equality must cover content, not SingleBg's timestamp/color-only equals. */
internal class WearHistoryCache<K : Any, V : Any>(private val heartbeatNanos: Long = Long.MAX_VALUE) {
    private var key: K? = null
    private var value: V? = null
    private var sentAt: Long? = null
    internal var builds = 0L; private set
    internal var sends = 0L; private set

    @Synchronized fun snapshot(newKey: K, now: Long, force: Boolean, build: () -> V): V? {
        val changed = key != newKey
        if (changed) { value = build(); key = newKey; builds++ }
        val previous = sentAt
        if (!changed && !force && previous != null && now >= previous && now - previous < heartbeatNanos) return null
        sentAt = now
        sends++
        return value
    }

    @Synchronized fun reconnect() { sentAt = null }
}

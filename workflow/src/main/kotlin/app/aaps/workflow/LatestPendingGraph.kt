package app.aaps.workflow

/** Presentation queue: one running snapshot plus one replaceable latest intent; no routine cancellations. */
internal class LatestPendingGraph<K : Any, V : Any> {
    data class Entry<K, V>(val key: K, val value: V)
    private var active: Entry<K, V>? = null
    private var pending: Entry<K, V>? = null
    var started = 0L; private set
    var completed = 0L; private set
    var coalesced = 0L; private set
    var discarded = 0L; private set
    @Synchronized fun offer(key: K, value: V): Entry<K, V>? {
        if (active?.key == key || pending?.key == key) return null
        if (active == null) return Entry(key, value).also { active = it; started++ }
        if (pending != null) coalesced++
        pending = Entry(key, value)
        return null
    }
    @Synchronized fun finish(key: K, published: Boolean): Entry<K, V>? {
        if (active?.key != key) return null
        completed++; if (!published) discarded++
        active = pending; pending = null
        if (active != null) started++
        return active
    }
    @Synchronized fun occupancy() = (if (active == null) 0 else 1) to (if (pending == null) 0 else 1)
}

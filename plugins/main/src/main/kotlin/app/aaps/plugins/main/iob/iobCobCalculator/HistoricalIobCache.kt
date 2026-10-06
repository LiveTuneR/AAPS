package app.aaps.plugins.main.iob.iobCobCalculator

import app.aaps.core.interfaces.aps.IobTotal
import java.util.TreeMap

/** Past insulin only. A revision fences calculations that were suspended across a DB mutation. */
internal class HistoricalIobCache(private val maximumEntries: Int = 4096, private val retentionMs: Long = 48 * 60 * 60 * 1000L) {
    init { require(maximumEntries > 0 && retentionMs > 0) }
    private val values = TreeMap<Long, IobTotal>()
    private var revision = 0L
    private var hits = 0L
    private var misses = 0L
    private var rejectedWrites = 0L
    private val invalidations = linkedMapOf<String, Long>()
    data class Lookup(val revision: Long, val value: IobTotal?)
    data class Stats(val hits: Long, val misses: Long, val entries: Int, val rejectedWrites: Long, val invalidations: Map<String, Long>)

    @Synchronized fun lookup(time: Long, now: Long): Lookup {
        prune(now)
        val value = if (time < now) values[time]?.detached() else null
        if (value == null) misses++ else hits++
        app.aaps.core.data.diagnostics.EnergyRuntimeCounters.add(if (value == null) "historicalIob.cacheMisses" else "historicalIob.cacheHits")
        return Lookup(revision, value)
    }

    @Synchronized fun store(time: Long, calculationStartedAt: Long, expectedRevision: Long, value: IobTotal): Boolean {
        if (revision != expectedRevision) { rejectedWrites++; return false }
        // A future calculation becoming past while suspended is not a historical snapshot.
        if (time < calculationStartedAt && time >= calculationStartedAt - retentionMs) {
            values[time] = value.detached()
            while (values.size > maximumEntries) values.pollFirstEntry()
        }
        return true
    }

    @Synchronized fun invalidate(reason: String, from: Long = Long.MIN_VALUE) {
        revision++
        values.tailMap(from, true).clear()
        invalidations[reason] = (invalidations[reason] ?: 0) + 1
    }

    @Synchronized fun prune(now: Long) { values.headMap(now - retentionMs, false).clear() }
    @Synchronized fun stats() = Stats(hits, misses, values.size, rejectedWrites, invalidations.toMap())
    private fun IobTotal.detached(): IobTotal = copy(iobWithZeroTemp = iobWithZeroTemp?.detached())
}

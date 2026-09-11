package app.aaps.core.interfaces.iob

import app.aaps.core.data.diagnostics.LoopHealthState
import app.aaps.core.interfaces.aps.Loop

/** Copies observable data only; never waits for a calculation or reads stored fallback ADS. */
fun IobCobCalculator.loopHealthSnapshot(loop: Loop): LoopHealthState {
    val store = ads
    return synchronized(store.dataLock) {
        val raw = store.bgReadings.firstOrNull()?.timestamp
        val bucket = store.bucketedData?.firstOrNull()?.timestamp
        val table = store.autosensDataTable
        val lastRun = loop.lastRun
        (loopHealth?.snapshot() ?: LoopHealthState()).copy(
            newestRawBgTimestamp = raw,
            newestBucketedBgTimestamp = bucket,
            lastBgTriggeredRun = loop.lastBgTriggeredRun.takeIf { it > 0 },
            autosensLastDataTimestamp = if (table.size() > 0) table.valueAt(table.size() - 1).time else null,
            autosensDataTableSize = table.size(),
            firstMissingIndex = store.bucketedData?.indexOfFirst { table[store.roundUpTime(it.timestamp)] == null }?.takeIf { it >= 0 },
            referenceTime = store.bucketReferenceTime,
            currentSensorPhaseOffsetMs = raw?.let { time -> store.bucketReferenceTime?.let { ((time - it) % 300_000 + 300_000) % 300_000 } },
            lastEnactTimestamp = lastRun?.let { maxOf(it.lastTBREnact, it.lastSMBEnact).takeIf { time -> time > 0 } }
        )
    }
}

package app.aaps.core.data.workflow

/** Persist only reload intent, never an APS result or an insulin command. */
data class CalculationIntent(
    val end: Long,
    val requestedAt: Long,
    val rawBgTimestamp: Long? = null,
    val invalidateFrom: Long? = null,
    val reloadBg: Boolean = true,
    val newBg: Boolean = false,
    val therapy: Boolean = false,
    val recovered: Boolean = false
) {
    fun merge(newer: CalculationIntent) = CalculationIntent(
        end = maxOf(end, newer.end),
        requestedAt = minOf(requestedAt, newer.requestedAt),
        rawBgTimestamp = listOfNotNull(rawBgTimestamp, newer.rawBgTimestamp).maxOrNull(),
        invalidateFrom = listOfNotNull(invalidateFrom, newer.invalidateFrom).minOrNull(),
        reloadBg = reloadBg || newer.reloadBg,
        newBg = newBg || newer.newBg,
        therapy = therapy || newer.therapy,
        recovered = recovered || newer.recovered
    )
}

data class ActiveCalculation(val generation: Long, val intent: CalculationIntent, val startedAt: Long, val valid: Boolean = true)

data class CalculationQueueState(
    val schemaVersion: Int = 1,
    val nextGeneration: Long = 1,
    val active: ActiveCalculation? = null,
    val pending: CalculationIntent? = null,
    val historyBarrier: Boolean = false,
    val lastClaimedBg: Long = 0,
    val lastClaimedGeneration: Long? = null,
    val coalescedBgCount: Long = 0,
    val completedCount: Long = 0,
    val cancelledCount: Long = 0,
    val staleRejectCount: Long = 0,
    val lastDurationMs: Long? = null
)

interface CalculationJournal {
    fun read(): CalculationQueueState?
    /** Must be durable/atomic or throw. Never acknowledge a failed persistence operation. */
    fun write(state: CalculationQueueState)
}

/** One owner, one replaceable pending intent. New BG never invalidates the active owner. */
class LatestPendingCalculation(private val journal: CalculationJournal) {
    private val lock = Any()
    private var state = journal.read() ?: CalculationQueueState()

    init {
        require(state.schemaVersion == 1) { "Unsupported calculation journal schema" }
        state.active?.let { interrupted ->
            val replay = interrupted.intent.copy(reloadBg = true, recovered = true)
            save(state.copy(active = null, pending = state.pending?.merge(replay) ?: replay, historyBarrier = false, cancelledCount = state.cancelledCount + 1))
        }
        if (state.historyBarrier) save(state.copy(historyBarrier = false, pending = state.pending?.copy(reloadBg = true, recovered = true)))
    }

    fun snapshot(): CalculationQueueState = synchronized(lock) { state }

    fun offer(intent: CalculationIntent) = synchronized(lock) {
        val wasPendingBg = state.pending?.newBg == true
        save(state.copy(
            pending = state.pending?.merge(intent) ?: intent,
            active = if (intent.therapy) state.active?.copy(valid = false) else state.active,
            historyBarrier = state.historyBarrier && !intent.therapy,
            coalescedBgCount = state.coalescedBgCount + if (intent.newBg && wasPendingBg) 1 else 0
        ))
    }

    /** Separate from offer: history mutation invalidates immediately, before its debounce expires. */
    fun invalidate() = synchronized(lock) {
        if (state.active?.valid == true) save(state.copy(active = state.active?.copy(valid = false)))
    }

    fun holdHistory(now: Long, invalidateFrom: Long = 0) = synchronized(lock) {
        val barrier = CalculationIntent(now, now, invalidateFrom = invalidateFrom, reloadBg = true, therapy = true)
        save(state.copy(historyBarrier = true, active = state.active?.copy(valid = false), pending = state.pending?.merge(barrier) ?: barrier))
    }

    fun startNext(now: Long): ActiveCalculation? = synchronized(lock) {
        if (state.active != null || state.historyBarrier) return null
        val pending = state.pending ?: return null
        val active = ActiveCalculation(state.nextGeneration, pending, now)
        save(state.copy(active = active, pending = null, nextGeneration = Math.addExact(state.nextGeneration, 1)))
        active
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) { state.active?.let { it.generation == generation && it.valid } == true }

    /** Called after the worker chain terminates, not when cancellation is merely requested. */
    fun finish(generation: Long, now: Long, succeeded: Boolean): Boolean = synchronized(lock) {
        val active = state.active?.takeIf { it.generation == generation } ?: return false
        save(state.copy(
            active = null,
            completedCount = state.completedCount + if (succeeded && active.valid) 1 else 0,
            cancelledCount = state.cancelledCount + if (!succeeded || !active.valid) 1 else 0,
            lastDurationMs = (now - active.startedAt).coerceAtLeast(0)
        ))
        true
    }

    /** Fail closed across process death between claiming and invoking Loop. No insulin retry is persisted. */
    fun claimBg(generation: Long, timestamp: Long, inMemoryWatermark: Long = 0, therapy: Boolean = false): Boolean = synchronized(lock) {
        if (state.active?.let { it.generation == generation && it.valid } != true) {
            save(state.copy(staleRejectCount = state.staleRejectCount + 1))
            return false
        }
        if (state.lastClaimedGeneration == generation) return false
        val previous = maxOf(state.lastClaimedBg, inMemoryWatermark)
        if (timestamp < previous || (timestamp == previous && (!therapy || state.active?.intent?.recovered == true))) return false
        save(state.copy(lastClaimedBg = maxOf(timestamp,previous), lastClaimedGeneration = generation))
        true
    }

    fun rejectStale() = synchronized(lock) { save(state.copy(staleRejectCount = state.staleRejectCount + 1)) }

    private fun save(next: CalculationQueueState) {
        journal.write(next)
        state = next
    }
}

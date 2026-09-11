package app.aaps.core.data.diagnostics

enum class LoopHealthStatus { UNKNOWN, HEALTHY, CALCULATING, DEGRADED, STALE }

data class LoopHealthState(
    val newestRawBgTimestamp: Long? = null,
    val newestBucketedBgTimestamp: Long? = null,
    val lastBgTriggeredRun: Long? = null,
    val autosensLastDataTimestamp: Long? = null,
    val autosensDataTableSize: Int = 0,
    val firstMissingIndex: Int? = null,
    val currentWorkflowJob: String? = null,
    val activeWorkflowGeneration: Long? = null,
    val currentCalculationStartedAt: Long? = null,
    val calculationRunning: Boolean = false,
    val lastCalculationDurationMs: Long? = null,
    val referenceTime: Long? = null,
    val currentSensorPhaseOffsetMs: Long? = null,
    val supersededAdsPublishSkipCount: Long = 0,
    val duplicateGlucoseMetadataEventCount: Long = 0,
    val therapyRelevantGlucoseUpdateCount: Long = 0,
    val lastCalculationSuccessTimestamp: Long? = null,
    val lastEnactTimestamp: Long? = null
) {
    fun age(timestamp: Long?, now: Long): Long? = timestamp?.takeIf { it > 0 && it <= now }?.let { now - it }
    fun status(now: Long): LoopHealthStatus {
        val rawAge = age(newestRawBgTimestamp, now) ?: return LoopHealthStatus.UNKNOWN
        if (rawAge > 9 * 60_000) return LoopHealthStatus.STALE
        if (calculationRunning && age(currentCalculationStartedAt, now)?.let { it > 2 * 60_000 } == true) return LoopHealthStatus.DEGRADED
        val loopAge = age(lastBgTriggeredRun, now)
        val adsAge = age(autosensLastDataTimestamp, now)
        if (loopAge != null && loopAge > 11 * 60_000 || adsAge != null && adsAge > 11 * 60_000) return LoopHealthStatus.DEGRADED
        if (calculationRunning) return LoopHealthStatus.CALCULATING
        if (loopAge == null || adsAge == null || lastCalculationSuccessTimestamp == null) return LoopHealthStatus.UNKNOWN
        return LoopHealthStatus.HEALTHY
    }
}

/** Passive, process-local counters. No scheduler, callback or therapy dependency. */
class LoopHealthTracker {
    @Volatile private var state = LoopHealthState()
    fun snapshot(): LoopHealthState = state
    @Synchronized fun started(job: String, generation: Long, now: Long) {
        state = state.copy(currentWorkflowJob = job, activeWorkflowGeneration = generation, currentCalculationStartedAt = now, calculationRunning = true)
    }
    @Synchronized fun invalidated() { state = state.copy(calculationRunning = false, activeWorkflowGeneration = null) }
    @Synchronized fun completed(generation: Long, now: Long) {
        if (state.activeWorkflowGeneration == generation) state = state.copy(
            calculationRunning = false, lastCalculationSuccessTimestamp = now,
            lastCalculationDurationMs = state.currentCalculationStartedAt?.let { (now - it).coerceAtLeast(0) })
    }
    @Synchronized fun publishSkipped() { state = state.copy(supersededAdsPublishSkipCount = state.supersededAdsPublishSkipCount + 1) }
    @Synchronized fun glucoseEvents(metadata: Int, relevant: Int) { state = state.copy(
        duplicateGlucoseMetadataEventCount = state.duplicateGlucoseMetadataEventCount + metadata,
        therapyRelevantGlucoseUpdateCount = state.therapyRelevantGlucoseUpdateCount + relevant) }
}

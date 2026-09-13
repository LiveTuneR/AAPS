package app.aaps.workflow

import android.content.Context
import android.os.SystemClock
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.aaps.core.data.workflow.CalculationIntent
import app.aaps.core.data.workflow.LatestPendingCalculation
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.UPDATE_PREDICTIONS
import app.aaps.core.utils.worker.then
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.util.UUID

@Singleton
class CalculationWorkflowImpl @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val workflowChainData: WorkflowChainData,
    private val mainSignals: CalculationSignalsEmitter,
    // Lazy: breaks Dagger cycle OverviewDataCache → Loop → IobCobCalculator → CalculationWorkflow → OverviewDataCache.
    // Side methods that use mainCache run at runtime, never during construction.
    private val mainCacheProvider: Provider<OverviewDataCache>
) : CalculationWorkflow {

    private val mainCache: OverviewDataCache get() = mainCacheProvider.get()

    // Held across slot-write + WM enqueue so both systems agree on which call won. Without this,
    // two concurrent runCalculation/runOnReceivedPredictions threads can interleave so the slot
    // ends up with gen N but WM ends up running work tagged gen N-1 (because REPLACE honors call
    // order, not generation order). The worker's gen check then fails and the calculation is
    // silently dropped. Lock is held only across the enqueue itself — microseconds, no real
    // contention cost.
    private val enqueueLock = Any()
    internal var observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var observeMainWork = true
    internal var schedulerOverride: LatestPendingCalculation? = null
    private val mainScheduler: LatestPendingCalculation by lazy {
        (schedulerOverride ?: LatestPendingCalculation(CalculationPreferenceJournal(
            context.getSharedPreferences("apex7-calculation-journal", Context.MODE_PRIVATE)
        ))).also { workflowChainData.mainScheduler = it }
    }
    private var mainBindings: PrepareGraphDataWorker.PrepareGraphData? = null
    @Inject lateinit var therapyTelemetry: Provider<app.aaps.core.interfaces.telemetry.TherapyTelemetry>

    init {
        // Verify definition
        var sumPercent = 0
        for (pass in CalculationWorkflow.ProgressData.entries) sumPercent += pass.percentOfTotal
        require(sumPercent == 100)
    }

    override fun stopCalculation(job: String, from: String, invalidateFrom: Long?) {
        synchronized(enqueueLock) {
            if (job == MAIN_CALCULATION) mainScheduler.holdHistory(dateUtil.now(), invalidateFrom ?: 0)
            workflowChainData.invalidate(job)
        }
        if (job == MAIN_CALCULATION) {
            // Invalidated work drains behind the single producer mutex; a BG never cancels it.
            aapsLogger.info(LTag.WORKER, "Scheduler stage=INVALIDATED reason=$from")
            return
        }
        aapsLogger.debug(LTag.WORKER, "Stopping calculation thread: $from")
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(job)
        val deadline = System.currentTimeMillis() + STOP_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val workStatus = workManager.getWorkInfosForUniqueWork(job).get()
            if (workStatus.isEmpty() || workStatus[0].state != WorkInfo.State.RUNNING) {
                aapsLogger.debug(LTag.WORKER, "Calculation thread stopped: $from")
                return
            }
            SystemClock.sleep(STOP_WAIT_POLL_MS)
        }
        aapsLogger.warn(LTag.WORKER, "Calculation thread did not stop within ${STOP_WAIT_TIMEOUT_MS}ms: $from")
    }

    override fun waitForCalculationFinish(job: String, reason: String) {
        val workManager = WorkManager.getInstance(context)
        val tag = prepareTag(job)
        val deadline = System.currentTimeMillis() + STOP_WAIT_TIMEOUT_MS
        var logged = false
        while (System.currentTimeMillis() < deadline) {
            // Old finished works keep their tag until WorkManager prunes them; only unfinished ones matter.
            val running = workManager.getWorkInfosByTag(tag).get().any { !it.state.isFinished }
            if (!running) return
            if (!logged) {
                aapsLogger.debug(LTag.AUTOSENS, "Waiting for calculation to finish: $reason")
                logged = true
            }
            SystemClock.sleep(STOP_WAIT_POLL_MS)
        }
        aapsLogger.warn(LTag.AUTOSENS, "Calculation did not finish within ${STOP_WAIT_TIMEOUT_MS}ms: $reason")
    }

    override fun runCalculation(
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        cache: OverviewDataCache,
        signals: CalculationSignalsEmitter,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        triggeredByNewBG: Boolean,
        invalidateFrom: Long?,
        rawBgTimestamp: Long?
    ) {
        aapsLogger.debug(LTag.WORKER, "Starting calculation worker: $reason to ${dateUtil.dateAndTimeAndSecondsString(end)}")

        val isMain = job == MAIN_CALCULATION
        val prepare = PrepareGraphDataWorker.PrepareGraphData(
            iobCobCalculator = iobCobCalculator,
            overviewData = overviewData,
            cache = cache,
            signals = signals,
            reason = reason,
            end = end,
            bgDataReload = bgDataReload,
            limitDataToOldestAvailable = isMain,
            triggeredByNewBG = triggeredByNewBG,
            // HISTORY ends here, so emit DRAW_FINAL inline. MAIN delegates to PostCalculationWorker.
            emitFinalProgress = !isMain,
            invalidateFrom = invalidateFrom
        )
        if (isMain) {
            synchronized(enqueueLock) {
                mainBindings = prepare
                val intent = CalculationIntent(end, dateUtil.now(), rawBgTimestamp ?: end.takeIf { triggeredByNewBG },
                    invalidateFrom, bgDataReload, triggeredByNewBG,
                    therapy = !triggeredByNewBG && reason != "onEventAppInitialized")
                mainScheduler.offer(intent)
                if (intent.therapy) workflowChainData.invalidate(MAIN_CALCULATION)
                logMainState("OFFER")
                startPendingMain()
            }
            return
        }
        synchronized(enqueueLock) {
            val generation = if (isMain) {
                val post = PostCalculationWorker.PostCalculationData(
                    overviewData = overviewData,
                    cache = cache,
                    signals = signals,
                    triggeredByNewBG = triggeredByNewBG,
                    runLoopAndWidgetPhase = true
                )
                workflowChainData.startMain(prepare, post)
            } else {
                workflowChainData.startHistory(prepare)
            }

            val jobData = dataForJob(job, generation)
            WorkManager.getInstance(context)
                .beginUniqueWork(
                    job, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequest.Builder(PrepareGraphDataWorker::class.java)
                        .setInputData(jobData)
                        // Tag only the data-producing stage so waitForCalculationFinish() can await it
                        // without waiting on the loop-invoking PostCalculationWorker (would self-stall).
                        .addTag(prepareTag(job))
                        .build()
                )
                .then(
                    runIf = isMain,
                    OneTimeWorkRequest.Builder(PostCalculationWorker::class.java)
                        .setInputData(jobData)
                        .build()
                )
                .enqueue()
        }
    }

    private fun startPendingMain() {
        val bindings = mainBindings ?: return
        val active = mainScheduler.startNext(dateUtil.now()) ?: return
        val intent = active.intent
        val prepare = PrepareGraphDataWorker.PrepareGraphData(bindings.iobCobCalculator, bindings.overviewData, bindings.cache,
            bindings.signals, if (intent.therapy) "OrderedHistory" else if (intent.recovered) "RecoveredReload" else "LatestBG",
            intent.end, intent.reloadBg || intent.recovered, true, intent.newBg, false, intent.invalidateFrom)
        val post = PostCalculationWorker.PostCalculationData(bindings.overviewData, bindings.cache, bindings.signals, intent.newBg, true,
            therapyRecalculation = intent.therapy && !intent.recovered)
        workflowChainData.startMain(prepare, post, active.generation)
        val input = dataForJob(MAIN_CALCULATION, active.generation)
        val tail = OneTimeWorkRequest.Builder(PostCalculationWorker::class.java).setInputData(input).build()
        // APPEND_OR_REPLACE preserves WM ordering across cancellation/restart. The in-process
        // queue (not WM KEEP) owns latest-pending coalescing; the producer mutex also drains stopped workers.
        try {
            val operation = WorkManager.getInstance(context).beginUniqueWork(MAIN_CALCULATION, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequest.Builder(PrepareGraphDataWorker::class.java).setInputData(input).addTag(prepareTag(MAIN_CALCULATION)).build())
                .then(tail).enqueue()
            operation.result.addListener({
                try { operation.result.get() }
                catch (error: Exception) { enqueueFailed(active.generation,error) }
            }, { action -> observerScope.launch { action.run() } })
        } catch (error: Exception) { enqueueFailed(active.generation,error); return }
        logMainState("START")
        if (observeMainWork) observeMainCompletion(tail.id, active.generation)
    }

    private fun observeMainCompletion(id: UUID, generation: Long) {
        observerScope.launch {
            var retry = 1000L
            while (mainScheduler.snapshot().active?.generation == generation) {
                try {
                    // Periodically re-check ownership if enqueue failed before WorkManager emitted a row.
                    val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                        WorkManager.getInstance(context).getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished }
                    } ?: continue
                    mainFinished(generation, result.state == WorkInfo.State.SUCCEEDED)
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    aapsLogger.error(LTag.WORKER, "Scheduler completion observation failed generation=$generation type=${error.javaClass.simpleName} retryMs=$retry")
                    delay(retry)
                    retry = (retry * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun enqueueFailed(generation: Long, error: Exception) = synchronized(enqueueLock) {
        val active = mainScheduler.snapshot().active?.takeIf { it.generation==generation } ?: return@synchronized
        aapsLogger.error(LTag.WORKER,"Scheduler enqueue failed generation=$generation type=${error.javaClass.simpleName}")
        // Only reload intent is retried. No result or insulin command is replayed.
        mainScheduler.finish(generation,dateUtil.now(),false)
        mainScheduler.offer(active.intent.copy(recovered=true,reloadBg=true))
        logMainState("ENQUEUE_RETRY_PENDING")
        observerScope.launch { delay(5000); synchronized(enqueueLock) { startPendingMain() } }
    }

    internal fun mainFinished(generation: Long, succeeded: Boolean) = synchronized(enqueueLock) {
        val active = mainScheduler.snapshot().active
        if (!mainScheduler.finish(generation, dateUtil.now(), succeeded)) return@synchronized
        if (!succeeded && active?.valid == true) {
            mainScheduler.offer(active.intent.copy(recovered = true, reloadBg = true))
            logMainState("RELOAD_RETRY_PENDING")
            observerScope.launch { delay(5000); synchronized(enqueueLock) { startPendingMain() } }
            return@synchronized
        }
        logMainState(if (succeeded) "FINISH" else "CANCEL_OR_FAILURE")
        startPendingMain()
    }

    private fun logMainState(stage: String) {
        val state = mainScheduler.snapshot()
        aapsLogger.info(LTag.WORKER, "Scheduler stage=$stage activeGeneration=${state.active?.generation} activeBg=${state.active?.intent?.rawBgTimestamp} pendingBg=${state.pending?.rawBgTimestamp} pendingHistory=${state.pending?.invalidateFrom} coalesced=${state.coalescedBgCount} completed=${state.completedCount} cancelled=${state.cancelledCount} staleRejects=${state.staleRejectCount} lastDurationMs=${state.lastDurationMs} oldestPendingAgeMs=${state.pending?.let { (dateUtil.now() - it.requestedAt).coerceAtLeast(0) }}")
        if (::therapyTelemetry.isInitialized) try {
            therapyTelemetry.get().record(app.aaps.core.interfaces.telemetry.TherapyEventType.SCHEDULER,org.json.JSONObject()
                .put("stage",stage).put("rawBgTimestamp",state.active?.intent?.rawBgTimestamp ?: org.json.JSONObject.NULL)
                .put("activeGeneration",state.active?.generation ?: org.json.JSONObject.NULL).put("activeStartedAt",state.active?.startedAt ?: org.json.JSONObject.NULL)
                .put("pendingBg",state.pending?.rawBgTimestamp ?: org.json.JSONObject.NULL).put("pendingHistory",state.pending?.invalidateFrom ?: org.json.JSONObject.NULL)
                .put("coalesced",state.coalescedBgCount).put("completed",state.completedCount).put("cancelled",state.cancelledCount)
                .put("staleRejects",state.staleRejectCount).put("calculationMs",state.lastDurationMs ?: org.json.JSONObject.NULL),state.active?.generation)
        } catch (error: Exception) { aapsLogger.error(LTag.WORKER,"Scheduler telemetry failed type=${error.javaClass.simpleName}") }
    }

    override fun runOnReceivedPredictions(overviewData: OverviewData) {
        aapsLogger.debug(LTag.WORKER, "Starting updateReceivedPredictions worker")

        synchronized(enqueueLock) {
            val generation = workflowChainData.startPredictions(
                PostCalculationWorker.PostCalculationData(
                    overviewData = overviewData,
                    cache = mainCache,
                    signals = mainSignals,
                    triggeredByNewBG = false,
                    runLoopAndWidgetPhase = false
                )
            )

            WorkManager.getInstance(context).enqueueUniqueWork(
                UPDATE_PREDICTIONS, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(PostCalculationWorker::class.java)
                    .setInputData(dataForJob(UPDATE_PREDICTIONS, generation))
                    .build()
            )
        }
    }

    // Distinct per-job tag on the Prepare (data-producing) worker; used by waitForCalculationFinish().
    private fun prepareTag(job: String): String = "$job:prepare"

    private fun dataForJob(job: String, generation: Long): Data =
        Data.Builder()
            .putString(WorkflowChainData.JOB_KEY, job)
            .putLong(WorkflowChainData.GEN_KEY, generation)
            .build()

    companion object {

        private const val STOP_WAIT_TIMEOUT_MS = 5_000L
        private const val STOP_WAIT_POLL_MS = 100L
    }
}

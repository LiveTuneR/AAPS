package app.aaps.implementation.telemetry

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.telemetry.TherapyEventType
import app.aaps.core.interfaces.telemetry.TherapyTelemetry
import app.aaps.core.interfaces.telemetry.TherapyTelemetryHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class TherapyTelemetryImpl @Inject constructor(
    private val context: Context,
    private val config: Config,
    private val logger: AAPSLogger,
    private val loggerUtils: LoggerUtils,
) : TherapyTelemetry {
    private val _health = MutableStateFlow(TherapyTelemetryHealth())
    override val health = _health.asStateFlow()
    private val started = AtomicBoolean()
    private val drops = AtomicLong()
    private val admissionFailures = AtomicLong()
    private val queueHighWater = AtomicLong()
    private val admissionLatency = LatencyWindow()
    private val storeAppendLatency = LatencyWindow()
    private val crashMarker by lazy { TherapyCrashMarker(File(context.filesDir,"therapy-crash-v1")) }
    private val admissionLedger by lazy { TelemetryAdmissionLedger(File(context.filesDir,"therapy-telemetry-admission-v1.jsonl")) }
    private val writer = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4096),
        { action -> Thread(action,"TherapyTelemetry").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val diagnostics = DiagnosticBatchBuffer()
    private val diagnosticFlushQueued = AtomicBoolean()
    private var lastHealthRefresh = Long.MIN_VALUE
    private val diagnosticClock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "TherapyDiagnosticFlush").apply { isDaemon = true }
    }
    private val diagnosticTypes = setOf(TherapyEventType.PUMP_STATE, TherapyEventType.ACTIVITY,
        TherapyEventType.CALCULATION, TherapyEventType.SCHEDULER)
    private val store by lazy { TherapyTelemetryStore(File(context.filesDir,"therapy-telemetry-v1"),config.HEAD,
        monotonic = { SystemClock.elapsedRealtimeNanos() }) }

    override fun start() {
        if (!started.compareAndSet(false,true)) return
        writer.execute {
            try {
                admissionLedger.pendingLoss()?.let { loss ->
                    store.integrity("RECORD_LOSS", loss.firstUtc, loss.lastUtc, loss.count, "accepted_not_committed_after_restart")
                    admissionLedger.acknowledgeLoss(loss.throughSequence)
                }
                crashMarker.recover { store.append(TherapyEventType.ERROR.name,TelemetrySanitizer.clean(it)) }
            }
            catch (error: Exception) { failed(error) }
        }
        diagnosticClock.scheduleAtFixedRate({ requestDiagnosticFlush() }, 1, 1, TimeUnit.SECONDS)
        record(TherapyEventType.PROCESS_START,JSONObject().put("appVersion",config.VERSION_NAME).put("model",Build.MODEL)
            .put("androidApi",Build.VERSION.SDK_INT).put("buildSha",config.HEAD))
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        if (previous!=null) Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            try { crashMarker.write(error,System.currentTimeMillis()) } catch (_: Throwable) { /* Preserve original termination even under OOM/disk failure. */ }
            finally { previous.uncaughtException(thread,error) }
        }
    }

    override fun record(type: TherapyEventType, data: JSONObject, generation: Long?, correlationId: String?) {
        try {
            val copied = TelemetrySanitizer.clean(data)
            val observedUtc = System.currentTimeMillis()
            val observedMonotonic = SystemClock.elapsedRealtimeNanos()
            val observedZone = ZoneId.systemDefault()
            if (type in diagnosticTypes) {
                val accepted = diagnostics.offer(DiagnosticRecord(type.name, copied, generation, correlationId,
                    observedUtc, observedMonotonic, observedZone))
                if (!accepted) { drops.incrementAndGet(); requestDiagnosticFlush() }
                else if (diagnostics.stats().records >= 128 || diagnostics.stats().bufferedBytes >= 60 * 1024) requestDiagnosticFlush()
                return
            }
            updateQueueHighWater(writer.queue.size + 1)
            writer.execute {
                try {
                    flushDiagnostics()
                    val admissionStarted = SystemClock.elapsedRealtimeNanos()
                    val admission = admissionLedger.admit(observedUtc, type.name)
                    admissionLatency.add(SystemClock.elapsedRealtimeNanos() - admissionStarted)
                    flushDrops()
                    val storeStarted = SystemClock.elapsedRealtimeNanos()
                    val accepted = store.append(type.name,copied,generation,correlationId,observedUtc,observedMonotonic,observedZone)
                    storeAppendLatency.add(SystemClock.elapsedRealtimeNanos() - storeStarted)
                    admissionLedger.commit(admission.sequence)
                    refreshHealth()
                    if (!accepted) logger.error(LTag.CORE,"TELEMETRY_STORAGE_PRESSURE writerDrops=${store.writerDrops}")
                } catch (error: Exception) { failed(error) }
            }
        } catch (error: Exception) {
            drops.incrementAndGet()
            admissionFailures.incrementAndGet()
            _health.value = _health.value.copy(writerDrops = _health.value.writerDrops + 1,lastErrorType=error.javaClass.simpleName)
            logger.error(LTag.CORE,"Telemetry enqueue failed type=${error.javaClass.simpleName}")
        }
    }

    override fun setRetentionDays(days: Int) {
        require(days in 4..14)
        try { writer.execute { try { store.setRetention(days); refreshHealth() } catch (e: Exception) { failed(e) } } }
        catch (e: RejectedExecutionException) { failed(e) }
    }

    override suspend fun export(startUtc: Long, endUtc: Long, expectedCgmIntervalMs: Long?): File = suspendCancellableCoroutine { continuation ->
        try { writer.execute {
            var output: File? = null
            try {
                flushDiagnostics()
                flushDrops()
                output = File(context.cacheDir, "AAPS_APEX_DEVICE_TEST_${System.currentTimeMillis()}_${config.HEAD.take(7)}.zip")
                val supportFiles = buildList {
                    File(loggerUtils.logDirectory).listFiles()?.filter { it.isFile && it.lastModified() >= startUtc && it.lastModified() <= endUtc + 86_400_000L }?.let(::addAll)
                    File(context.filesDir, "apex/bolus-operations.json").takeIf(File::isFile)?.let(::add)
                    File(context.filesDir, "apex-diagnostics").listFiles()?.filter { it.isFile && it.lastModified() >= startUtc }?.let(::addAll)
                    File(context.filesDir, "medtrum-ble-diagnostics").listFiles()?.filter { it.isFile && it.lastModified() >= startUtc }?.let(::addAll)
                }
                store.export(
                    output, startUtc, endUtc, expectedCgmIntervalMs, supportFiles,
                    JSONObject().put("sourceSha", config.HEAD).put("appVersion", config.VERSION_NAME)
                        .put("phoneModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
                        .put("sessionStartUtc", startUtc).put("sessionEndUtc", endUtc)
                        .put("status", "EXPERIMENTAL_DEVICE_VALIDATION_REQUIRED")
                        .put("diagnosticPolicy", JSONObject()
                            .put("batchedTypes", org.json.JSONArray(diagnosticTypes.map { it.name }.sorted()))
                            .put("maxRecords",128).put("maxBytes",65536).put("flushIntervalMs",1000)
                            .put("aggregation", "consecutive identical PUMP_STATE; firstSeenUtc/lastSeenUtc/repeatCount")
                            .put("crashCoverage", "Unflushed RAM diagnostics may be lost; unclean session is marked uncertain. Accepted durable batches use counted admission WAL.")
                            .put("rawBleCoverage", "Bounded in-memory ring; explicit driver export reports truncation. Full BLE history is not promised.")
                            .put("criticalEvents", "Serialized writer admission and per-record fsync; record API asynchronous; bolus-operation journal unchanged"))
                        .put("telemetryPerformance", performanceJson()),
                )
                refreshHealth()
                if (continuation.isActive) continuation.resume(output) else output.delete()
            } catch (error: Exception) {
                output?.delete()
                failed(error)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        } } catch (error: Exception) { continuation.resumeWithException(error) }
    }

    private fun requestDiagnosticFlush() {
        if (!started.get() || !diagnosticFlushQueued.compareAndSet(false, true)) return
        try { writer.execute {
            try { flushDiagnostics(); flushDrops(); refreshHealth() }
            catch (error: Exception) { failed(error) }
            finally { diagnosticFlushQueued.set(false) }
        } } catch (error: RejectedExecutionException) { diagnosticFlushQueued.set(false) }
    }

    private fun flushDiagnostics() {
        val batch = diagnostics.drain()
        if (batch.isEmpty()) return
        // One admission per bounded batch; persisted counts also cover a partial append after a crash.
        val observations = batch.sumOf { it.data.optJSONObject("diagnosticAggregation")?.optLong("repeatCount", 1L) ?: 1L }
        val lastUtc = batch.maxOf { it.data.optJSONObject("diagnosticAggregation")?.optLong("lastSeenUtc", it.utc) ?: it.utc }
        val admission = admissionLedger.admit(batch.minOf { it.utc }, "DIAGNOSTIC_BATCH", observations, lastUtc)
        for (record in batch) store.append(record.type, record.data, record.generation, record.correlationId,
            record.utc, record.monotonic, record.zone, durable = false)
        store.flush()
        admissionLedger.commit(admission.sequence)
    }

    private fun flushDrops() {
        val count=drops.getAndSet(0)
        if (count > 0) try { store.dropped(count=count) } catch (error: Exception) { drops.addAndGet(count); throw error }
    }
    private fun failed(error: Exception) {
        drops.incrementAndGet()
        _health.value=_health.value.copy(lastErrorType=error.javaClass.simpleName)
        logger.error(LTag.CORE,"TherapyTelemetry failure type=${error.javaClass.simpleName}")
    }
    private fun refreshHealth() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastHealthRefresh != Long.MIN_VALUE && now - lastHealthRefresh < 1_000_000_000L) return
        lastHealthRefresh = now
        val admission = admissionLatency.snapshot()
        val append = storeAppendLatency.snapshot()
        _health.value=TherapyTelemetryHealth(retentionDays=store.retentionDays,bytesOnDisk=store.bytesOnDisk(),writerDrops=store.writerDrops,
            recoveredRecords=store.recoveredRecords,corruptedRecords=store.corruptedRecords,storagePressure=store.pressure,uncleanSessions=store.uncleanSessions,
            lastErrorType=_health.value.lastErrorType,
            admissionLatencyP50Ms=admission.p50Ms,admissionLatencyP95Ms=admission.p95Ms,admissionLatencyP99Ms=admission.p99Ms,admissionLatencyMaxMs=admission.maxMs,
            storeAppendLatencyP50Ms=append.p50Ms,storeAppendLatencyP95Ms=append.p95Ms,storeAppendLatencyP99Ms=append.p99Ms,storeAppendLatencyMaxMs=append.maxMs,
            queueDepth=writer.queue.size,queueHighWater=queueHighWater.get().toInt(),admissionFailures=admissionFailures.get())
    }

    private fun updateQueueHighWater(value: Int) {
        while (true) {
            val previous = queueHighWater.get()
            if (value <= previous || queueHighWater.compareAndSet(previous, value.toLong())) return
        }
    }

    private fun performanceJson(): JSONObject {
        val admission = admissionLatency.snapshot()
        val append = storeAppendLatency.snapshot()
        fun latency(value: LatencySnapshot) = JSONObject().put("p50Ms",value.p50Ms).put("p95Ms",value.p95Ms)
            .put("p99Ms",value.p99Ms).put("maxMs",value.maxMs).put("samples",value.samples)
        return JSONObject().put("admissionLatency",latency(admission)).put("storeAppendLatency",latency(append))
            .put("queueDepth",writer.queue.size).put("queueHighWater",queueHighWater.get())
            .put("admissionFailures",admissionFailures.get())
            .put("diagnosticBuffer", JSONObject().apply { val stats=diagnostics.stats()
                put("maxRecords",128).put("maxBytes",65536).put("flushIntervalMs",1000)
                put("records",stats.records).put("bufferedBytes",stats.bufferedBytes)
                put("offeredBytes",stats.offeredBytes).put("aggregatedRepeats",stats.repeats).put("rejected",stats.rejected)
            })
            .put("storeIo",JSONObject().apply { val stats=store.ioStats()
                put("appendCalls",stats.appendCalls).put("durableSyncs",stats.durableSyncs).put("directoryScans",stats.directoryScans)
            })
    }
}

private data class LatencySnapshot(val p50Ms: Double, val p95Ms: Double, val p99Ms: Double, val maxMs: Double, val samples: Int)

private class LatencyWindow(private val capacity: Int = 2048) {
    private val values = LongArray(capacity)
    private var count = 0
    private var cursor = 0

    @Synchronized fun add(nanos: Long) {
        values[cursor] = nanos.coerceAtLeast(0L)
        cursor = (cursor + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized fun snapshot(): LatencySnapshot {
        if (count == 0) return LatencySnapshot(0.0,0.0,0.0,0.0,0)
        val sorted = values.copyOf(count).sortedArray()
        fun percentile(value: Double): Double = sorted[((sorted.lastIndex * value).toInt()).coerceIn(0,sorted.lastIndex)] / 1_000_000.0
        return LatencySnapshot(percentile(0.50),percentile(0.95),percentile(0.99),sorted.last()/1_000_000.0,count)
    }
}

package app.aaps.implementation.telemetry

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
    private val logger: AAPSLogger
) : TherapyTelemetry {
    private val _health = MutableStateFlow(TherapyTelemetryHealth())
    override val health = _health.asStateFlow()
    private val started = AtomicBoolean()
    private val drops = AtomicLong()
    private val crashMarker by lazy { TherapyCrashMarker(File(context.filesDir,"therapy-crash-v1")) }
    private val writer = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4096),
        { action -> Thread(action,"TherapyTelemetry").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val store by lazy { TherapyTelemetryStore(File(context.filesDir,"therapy-telemetry-v1"),config.HEAD,
        monotonic = { SystemClock.elapsedRealtimeNanos() }) }

    override fun start() {
        if (!started.compareAndSet(false,true)) return
        record(TherapyEventType.PROCESS_START,JSONObject().put("appVersion",config.VERSION_NAME).put("model",Build.MODEL)
            .put("androidApi",Build.VERSION.SDK_INT).put("buildSha",config.HEAD))
        writer.execute {
            try { crashMarker.recover { store.append(TherapyEventType.ERROR.name,TelemetrySanitizer.clean(it)) } }
            catch (error: Exception) { failed(error) }
        }
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
            writer.execute {
                try {
                    flushDrops()
                    val accepted = store.append(type.name,copied,generation,correlationId,observedUtc,observedMonotonic,observedZone)
                    refreshHealth()
                    if (!accepted) logger.error(LTag.CORE,"TELEMETRY_STORAGE_PRESSURE writerDrops=${store.writerDrops}")
                } catch (error: Exception) { failed(error) }
            }
        } catch (error: Exception) {
            drops.incrementAndGet()
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
                flushDrops()
                output = File.createTempFile("aaps-therapy-", ".zip",context.cacheDir)
                store.export(output,startUtc,endUtc,expectedCgmIntervalMs)
                refreshHealth()
                if (continuation.isActive) continuation.resume(output) else output.delete()
            } catch (error: Exception) {
                output?.delete()
                failed(error)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        } } catch (error: Exception) { continuation.resumeWithException(error) }
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
        _health.value=TherapyTelemetryHealth(retentionDays=store.retentionDays,bytesOnDisk=store.bytesOnDisk(),writerDrops=store.writerDrops,
            recoveredRecords=store.recoveredRecords,corruptedRecords=store.corruptedRecords,storagePressure=store.pressure,uncleanSessions=store.uncleanSessions)
    }
}

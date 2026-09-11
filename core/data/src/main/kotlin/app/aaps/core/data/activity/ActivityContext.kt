package app.aaps.core.data.activity

enum class ActivitySource { SAMSUNG_HEALTH, WEAR_STEPS, PHONE, MANUAL, UNKNOWN }
enum class ActivityCategory { WALKING, RUNNING, CYCLING, POOL_SWIMMING, OPEN_WATER_SWIMMING, OTHER, UNKNOWN }
enum class ActivityState { ACTIVE, POST_ACTIVITY, STALE_ACTIVITY, NONE }
enum class ActivityAccess { AVAILABLE, UNAVAILABLE, PERMISSION_DENIED, SDK_NOT_CONFIGURED, ERROR }

data class ActivityEvent(
    val id: String,
    val source: ActivitySource,
    val rawType: String,
    val category: ActivityCategory,
    val startTime: Long,
    val endTime: Long? = null,
    val receivedAt: Long,
    val lastUpdatedAt: Long,
    val sourceDevice: String? = null,
    val steps: Long? = null,
    val heartRate: Double? = null
) {
    val detectionLatencyMs: Long get() = receivedAt - startTime
}

data class ActivityContext(
    val state: ActivityState = ActivityState.NONE,
    val event: ActivityEvent? = null,
    val access: ActivityAccess = ActivityAccess.SDK_NOT_CONFIGURED,
    val lastSuccessfulRead: Long? = null,
    val watchReachable: Boolean? = null,
    val clockSkew: Boolean = false
) {
    val usedForDosing: Boolean get() = false
}

/** Bounded read-only observation store. Activity is never converted into a dosing factor. */
class ActivityContextStore(restored: List<ActivityEvent> = emptyList()) {
    private val records = LinkedHashMap<Pair<ActivitySource, String>, ActivityEvent>()
    private var access = ActivityAccess.SDK_NOT_CONFIGURED
    private var lastRead: Long? = null
    init { restored.forEach { accept(it) } }

    @Synchronized fun sourceHealth(access: ActivityAccess, successfulReadAt: Long? = null) {
        this.access = access
        if (access == ActivityAccess.AVAILABLE) lastRead = successfulReadAt ?: lastRead
    }

    @Synchronized fun accept(event: ActivityEvent): Boolean {
        if (event.id.isBlank() || event.startTime <= 0 || event.lastUpdatedAt <= 0 || event.receivedAt <= 0 ||
            event.endTime?.let { it < event.startTime } == true || event.steps?.let { it < 0 } == true ||
            event.heartRate?.let { !it.isFinite() || it < 0 } == true) return false
        val key = event.source to event.id
        val previous = records[key]
        if (previous != null && event.lastUpdatedAt <= previous.lastUpdatedAt) return false
        // Polling an unchanged record must not refresh its signal age or first-receipt latency.
        records[key] = event.copy(receivedAt = previous?.receivedAt ?: event.receivedAt)
        while (records.size > 256) records.remove(records.minBy { it.value.lastUpdatedAt }.key)
        return true
    }

    @Synchronized fun export(): List<ActivityEvent> = records.values.toList()

    @Synchronized fun snapshot(now: Long): ActivityContext {
        val identified = records.values.filter { it.category != ActivityCategory.UNKNOWN }
        val selected = (identified.ifEmpty { records.values.toList() }).maxWithOrNull(compareBy<ActivityEvent> { it.endTime ?: it.lastUpdatedAt }.thenBy { it.startTime })
            ?: return ActivityContext(access = access, lastSuccessfulRead = lastRead)
        val skew = selected.startTime > now || selected.lastUpdatedAt > now || selected.receivedAt > now || selected.endTime?.let { it > now } == true
        val state = when {
            skew || access != ActivityAccess.AVAILABLE || selected.category == ActivityCategory.UNKNOWN -> ActivityState.STALE_ACTIVITY
            selected.endTime != null -> if (now - selected.endTime <= POST_WINDOW_MS) ActivityState.POST_ACTIVITY else ActivityState.STALE_ACTIVITY
            now - selected.lastUpdatedAt > SIGNAL_MAX_AGE_MS -> ActivityState.STALE_ACTIVITY
            else -> ActivityState.ACTIVE
        }
        return ActivityContext(state, selected, access, lastRead, clockSkew = skew)
    }

    companion object {
        const val SIGNAL_MAX_AGE_MS = 15 * 60_000L
        const val POST_WINDOW_MS = 2 * 3_600_000L
    }
}

/** SDK adapter boundary. Implementations must request READ exercise permission only. */
interface SamsungExerciseReader {
    suspend fun readExercises(since: Long): SamsungExerciseRead
}

data class SamsungExerciseRead(val access: ActivityAccess, val events: List<ActivityEvent> = emptyList())

class SamsungActivityProvider(private val reader: SamsungExerciseReader, private val store: ActivityContextStore) {
    suspend fun refresh(now: Long) {
        val result = try { reader.readExercises(now - 24 * 3_600_000L) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: SecurityException) { SamsungExerciseRead(ActivityAccess.PERMISSION_DENIED) }
        catch (_: Exception) { SamsungExerciseRead(ActivityAccess.ERROR) }
        store.sourceHealth(result.access, if (result.access == ActivityAccess.AVAILABLE) now else null)
        if (result.access == ActivityAccess.AVAILABLE) result.events
            .filter { it.source == ActivitySource.SAMSUNG_HEALTH }
            .forEach { store.accept(it) }
    }
}

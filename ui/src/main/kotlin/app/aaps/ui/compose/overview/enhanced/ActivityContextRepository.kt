package app.aaps.ui.compose.overview.enhanced

import android.content.Context
import android.os.SystemClock
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.data.activity.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Private on-device cache; there is intentionally no exported broadcast receiver or therapy consumer. */
@Singleton
class ActivityContextRepository @Inject constructor(@ApplicationContext private val context: Context) {
    @Inject lateinit var telemetry: javax.inject.Provider<app.aaps.core.interfaces.telemetry.TherapyTelemetry>
    private val preferences = context.getSharedPreferences("activity_context_shadow", Context.MODE_PRIVATE)
    private val store = ActivityContextStore(ActivityEventCodec.decode(preferences.getString("events_v1", null)))
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )
    fun snapshot(now: Long): ActivityContext = store.snapshot(now)
    @Synchronized fun accept(event: ActivityEvent): Boolean {
        if (!store.accept(event)) return false
        preferences.edit().putString("events_v1", ActivityEventCodec.encode(store.export())).apply()
        revision.value++
        recordTelemetry()
        return true
    }
    @Synchronized fun sourceHealth(access: ActivityAccess, lastRead: Long? = null, latencyMs: Long? = null) {
        store.sourceHealth(access, lastRead, latencyMs)
        revision.value++
        recordTelemetry()
    }

    suspend fun refresh(now: Long = System.currentTimeMillis()) {
        val started = SystemClock.elapsedRealtime()
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            sourceHealth(ActivityAccess.HEALTH_CONNECT_UNAVAILABLE, latencyMs = SystemClock.elapsedRealtime() - started)
            return
        }
        val client = HealthConnectClient.getOrCreate(context)
        try {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(requiredPermissions)) {
                sourceHealth(ActivityAccess.PERMISSION_REQUIRED, latencyMs = SystemClock.elapsedRealtime() - started)
                return
            }
            val from = Instant.ofEpochMilli(now - 24L * 3_600_000L)
            val until = Instant.ofEpochMilli(now)
            val filter = TimeRangeFilter.between(from, until)
            val exercises = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter)).records
            val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, filter)).records
            val heartRates = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, filter)).records
            exercises.forEach { exercise -> accept(exercise.toActivityEvent(now, steps, heartRates)) }
            sourceHealth(
                if (exercises.isEmpty()) ActivityAccess.NO_DATA else ActivityAccess.AVAILABLE,
                now,
                SystemClock.elapsedRealtime() - started,
            )
        } catch (_: SecurityException) {
            sourceHealth(ActivityAccess.PERMISSION_REQUIRED, latencyMs = SystemClock.elapsedRealtime() - started)
        } catch (_: Exception) {
            sourceHealth(ActivityAccess.ERROR, latencyMs = SystemClock.elapsedRealtime() - started)
        }
    }

    private fun recordTelemetry() {
        if (!::telemetry.isInitialized) return
        try {
            val value=store.snapshot(System.currentTimeMillis())
            telemetry.get().record(app.aaps.core.interfaces.telemetry.TherapyEventType.ACTIVITY,
                JSONObject().put("activityState",value.state.name).put("availability",value.access.name)
                    .put("source",value.event?.source?.name ?: JSONObject.NULL).put("usedForDosing",false)
                    .put("sourcePackage", value.event?.sourcePackage ?: JSONObject.NULL)
                    .put("eventTimestamp",value.event?.startTime ?: JSONObject.NULL).put("endTimestamp",value.event?.endTime ?: JSONObject.NULL)
                    .put("lastUpdatedAt",value.event?.lastUpdatedAt ?: JSONObject.NULL).put("clockSkew",value.clockSkew))
        } catch (_: Exception) { /* Never affect the shadow activity provider. */ }
    }
}

internal object ActivityEventCodec {
    fun encode(events: List<ActivityEvent>): String = JSONArray().apply {
        events.forEach { event -> put(JSONObject().apply {
            put("id", event.id); put("source", event.source.name); put("rawType", event.rawType); put("category", event.category.name)
            put("start", event.startTime); put("end", event.endTime); put("received", event.receivedAt); put("updated", event.lastUpdatedAt)
            put("device", event.sourceDevice); put("sourcePackage", event.sourcePackage); put("steps", event.steps); put("hr", event.heartRate); put("latestHr", event.latestHeartRate)
        }) }
    }.toString()

    fun decode(json: String?): List<ActivityEvent> {
        if (json == null || json.length > 512_000) return emptyList()
        return try {
            val array = JSONArray(json)
            if (array.length() > 256) return emptyList()
            (0 until array.length()).mapNotNull { index ->
                try {
                    val value = array.getJSONObject(index)
                    ActivityEvent(value.getString("id"), ActivitySource.valueOf(value.getString("source")), value.getString("rawType"),
                        ActivityCategory.valueOf(value.getString("category")), value.getLong("start"),
                        if (value.has("end")) value.getLong("end") else null, value.getLong("received"), value.getLong("updated"),
                        value.optString("device").ifBlank { null }, value.optString("sourcePackage").ifBlank { null }, if (value.has("steps")) value.getLong("steps") else null,
                        if (value.has("hr")) value.getDouble("hr") else null,
                        if (value.has("latestHr")) value.getDouble("latestHr") else null)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}

private fun ExerciseSessionRecord.toActivityEvent(
    receivedAt: Long,
    stepRecords: List<StepsRecord>,
    heartRateRecords: List<HeartRateRecord>,
): ActivityEvent {
    val originPackage = metadata.dataOrigin.packageName
    val source = if (originPackage.contains("shealth", ignoreCase = true) || originPackage.contains("samsung", ignoreCase = true))
        ActivitySource.SAMSUNG_HEALTH else ActivitySource.PHONE
    val samples = heartRateRecords.filter { it.endTime >= startTime && it.startTime <= endTime }
        .flatMap(HeartRateRecord::samples).sortedBy { it.time }
    val device = metadata.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ").ifBlank { null } }
    return ActivityEvent(
        id = metadata.id,
        source = source,
        rawType = exerciseType.toString(),
        category = when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ActivityCategory.WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> ActivityCategory.RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> ActivityCategory.CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> ActivityCategory.POOL_SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> ActivityCategory.OPEN_WATER_SWIMMING
            else -> ActivityCategory.OTHER
        },
        startTime = startTime.toEpochMilli(),
        endTime = endTime.toEpochMilli(),
        receivedAt = receivedAt,
        lastUpdatedAt = metadata.lastModifiedTime.toEpochMilli(),
        sourceDevice = device,
        sourcePackage = originPackage,
        steps = stepRecords.filter { it.endTime >= startTime && it.startTime <= endTime }.sumOf(StepsRecord::count).takeIf { it > 0 },
        heartRate = samples.map { it.beatsPerMinute.toDouble() }.average().takeIf(Double::isFinite),
        latestHeartRate = samples.lastOrNull()?.beatsPerMinute?.toDouble(),
    )
}

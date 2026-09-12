package app.aaps.ui.compose.overview.enhanced

import android.content.Context
import app.aaps.core.data.activity.*
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Private on-device cache; there is intentionally no exported broadcast receiver or therapy consumer. */
@Singleton
class ActivityContextRepository @Inject constructor(context: Context) {
    private val preferences = context.getSharedPreferences("activity_context_shadow", Context.MODE_PRIVATE)
    private val store = ActivityContextStore(ActivityEventCodec.decode(preferences.getString("events_v1", null)))
    fun snapshot(now: Long): ActivityContext = store.snapshot(now)
    @Synchronized fun accept(event: ActivityEvent): Boolean {
        if (!store.accept(event)) return false
        preferences.edit().putString("events_v1", ActivityEventCodec.encode(store.export())).apply()
        return true
    }
    fun sourceHealth(access: ActivityAccess, lastRead: Long? = null) = store.sourceHealth(access, lastRead)
}

internal object ActivityEventCodec {
    fun encode(events: List<ActivityEvent>): String = JSONArray().apply {
        events.forEach { event -> put(JSONObject().apply {
            put("id", event.id); put("source", event.source.name); put("rawType", event.rawType); put("category", event.category.name)
            put("start", event.startTime); put("end", event.endTime); put("received", event.receivedAt); put("updated", event.lastUpdatedAt)
            put("device", event.sourceDevice); put("steps", event.steps); put("hr", event.heartRate)
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
                        value.optString("device").ifBlank { null }, if (value.has("steps")) value.getLong("steps") else null,
                        if (value.has("hr")) value.getDouble("hr") else null)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}

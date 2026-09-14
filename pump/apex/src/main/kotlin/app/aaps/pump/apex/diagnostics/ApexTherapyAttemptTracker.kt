package app.aaps.pump.apex.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ApexTherapyAttempt(
    val timestamp: Long,
    val requestType: String,
    val requestedAmount: Double,
    val durationMinutes: Int?,
    val result: String,
    val failureLayer: String?,
    val reason: String?,
)

@Singleton
class ApexTherapyAttemptTracker @Inject constructor(
    @ApplicationContext context: Context,
    private val trace: ApexTrace,
) {
    private val preferences = context.getSharedPreferences("apex-therapy-diagnostics", Context.MODE_PRIVATE)

    @Volatile
    private var last = preferences.getString(KEY_LAST, null)?.let(::decode)

    fun current(): ApexTherapyAttempt? = last

    fun record(
        requestType: String,
        requestedAmount: Double,
        durationMinutes: Int? = null,
        result: String,
        failureLayer: String? = null,
        reason: String? = null,
    ) {
        val attempt = ApexTherapyAttempt(
            timestamp = System.currentTimeMillis(),
            requestType = requestType,
            requestedAmount = requestedAmount,
            durationMinutes = durationMinutes,
            result = result,
            failureLayer = failureLayer,
            reason = reason,
        )
        last = attempt
        preferences.edit().putString(KEY_LAST, encode(attempt).toString()).apply()
        trace.record(
            "therapy_enactment_attempt",
            fields = mapOf(
                "requestType" to requestType,
                "requestedAmount" to requestedAmount,
                "durationMinutes" to durationMinutes,
                "result" to result,
                "failureLayer" to failureLayer,
                "reason" to reason,
            ),
        )
    }

    private fun encode(value: ApexTherapyAttempt) = JSONObject()
        .put("timestamp", value.timestamp)
        .put("requestType", value.requestType)
        .put("requestedAmount", value.requestedAmount)
        .put("durationMinutes", value.durationMinutes)
        .put("result", value.result)
        .put("failureLayer", value.failureLayer)
        .put("reason", value.reason)

    private fun decode(raw: String): ApexTherapyAttempt? = runCatching {
        val value = JSONObject(raw)
        ApexTherapyAttempt(
            timestamp = value.getLong("timestamp"),
            requestType = value.getString("requestType"),
            requestedAmount = value.getDouble("requestedAmount"),
            durationMinutes = value.optInt("durationMinutes").takeIf { value.has("durationMinutes") && !value.isNull("durationMinutes") },
            result = value.getString("result"),
            failureLayer = value.optString("failureLayer").takeIf(String::isNotBlank),
            reason = value.optString("reason").takeIf(String::isNotBlank),
        )
    }.getOrNull()

    private companion object {
        const val KEY_LAST = "last-attempt"
    }
}

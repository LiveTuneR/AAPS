package app.aaps.workflow

import android.content.SharedPreferences
import app.aaps.core.data.workflow.ActiveCalculation
import app.aaps.core.data.workflow.CalculationIntent
import app.aaps.core.data.workflow.CalculationJournal
import app.aaps.core.data.workflow.CalculationQueueState
import org.json.JSONObject

/** A separate private preference file stores reload intent and a fail-closed BG claim watermark. */
internal class CalculationPreferenceJournal(private val preferences: SharedPreferences) : CalculationJournal {
    override fun read(): CalculationQueueState? = preferences.getString(KEY, null)?.let { encoded ->
        val json = JSONObject(encoded)
        CalculationQueueState(
            schemaVersion = json.getInt("schemaVersion"),
            nextGeneration = json.getLong("nextGeneration"),
            active = json.optJSONObject("active")?.let { ActiveCalculation(it.getLong("generation"), intent(it.getJSONObject("intent")), it.getLong("startedAt"), it.getBoolean("valid")) },
            pending = json.optJSONObject("pending")?.let(::intent),
            historyBarrier = json.optBoolean("historyBarrier"),
            lastClaimedBg = json.getLong("lastClaimedBg"),
            lastClaimedGeneration = json.nullableLong("lastClaimedGeneration"),
            coalescedBgCount = json.optLong("coalescedBgCount"),
            completedCount = json.optLong("completedCount"),
            cancelledCount = json.optLong("cancelledCount"),
            staleRejectCount = json.optLong("staleRejectCount"),
            lastDurationMs = json.nullableLong("lastDurationMs")
        )
    }

    override fun write(state: CalculationQueueState) {
        val json = JSONObject()
            .put("schemaVersion", state.schemaVersion).put("nextGeneration", state.nextGeneration)
            .put("active", state.active?.let { JSONObject().put("generation",it.generation).put("intent", encode(it.intent)).put("startedAt",it.startedAt).put("valid",it.valid) })
            .put("pending", state.pending?.let(::encode)).put("lastClaimedBg",state.lastClaimedBg)
            .put("historyBarrier",state.historyBarrier).put("lastClaimedGeneration",state.lastClaimedGeneration)
            .put("coalescedBgCount",state.coalescedBgCount).put("completedCount",state.completedCount)
            .put("cancelledCount",state.cancelledCount).put("staleRejectCount",state.staleRejectCount)
            .put("lastDurationMs",state.lastDurationMs)
        check(preferences.edit().putString(KEY,json.toString()).commit()) { "Calculation journal persistence failed" }
    }

    private fun encode(value: CalculationIntent) = JSONObject().put("end",value.end).put("requestedAt",value.requestedAt)
        .put("rawBgTimestamp",value.rawBgTimestamp).put("invalidateFrom",value.invalidateFrom)
        .put("reloadBg",value.reloadBg).put("newBg",value.newBg).put("therapy",value.therapy).put("recovered",value.recovered)

    private fun intent(json: JSONObject) = CalculationIntent(json.getLong("end"),json.getLong("requestedAt"),json.nullableLong("rawBgTimestamp"),
        json.nullableLong("invalidateFrom"),json.getBoolean("reloadBg"),json.getBoolean("newBg"),json.getBoolean("therapy"),json.getBoolean("recovered"))

    private fun JSONObject.nullableLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    companion object { const val KEY = "calculation-state-v1" }
}

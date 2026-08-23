package app.aaps.pump.medtrum.bench

import org.json.JSONArray
import org.json.JSONObject

class BenchRestartReport(private val campaignId: String) {
    private val events = mutableListOf<BenchRestartEvent>()

    fun record(event: BenchRestartEvent) {
        events += event
    }

    fun toJson(result: BenchRestartResult): String {
        val baseline = fieldsOf("bench_restart_baseline_snapshot")
        val before = fieldsOf("bench_restart_pre_transition_snapshot")
        val after = fieldsOf("bench_restart_activate_verify")
        val subsequent = fieldsOf("bench_restart_final_snapshot")
        return JSONObject()
            .put("schema", 1)
            .put("campaignId", campaignId)
            .put("finalVerdict", verdict(result))
            .put("message", result.message)
            .put("restartProven", result.restartProven)
            .put("baseline", JSONObject(baseline))
            .put("setPatchProbe", eventGroup("bench_restart_settings_"))
            .put("restartCandidate", eventGroup("bench_restart_transition_"))
            .put("activate", eventGroup("bench_restart_activate_"))
            .put("restartEvidence", restartEvidence(before, after, subsequent))
            .put("commandSummary", commandSummary())
            .put("errorsAndTimeouts", eventArray { event ->
                event.name.contains("failed") || event.name.contains("blocked") || event.name.contains("timeout")
            })
            .put("campaignStateTransitions", eventArray { true })
            .put("finalDeviceState", (baseline + before + after + subsequent)["realPumpState"] ?: JSONObject.NULL)
            .toString(2)
    }

    fun toMarkdown(result: BenchRestartResult): String {
        val baseline = fieldsOf("bench_restart_baseline_snapshot")
        val before = fieldsOf("bench_restart_pre_transition_snapshot")
        val after = fieldsOf("bench_restart_activate_verify")
        val subsequent = fieldsOf("bench_restart_final_snapshot")
        return buildString {
            appendLine("# Medtrum bench restart campaign")
            appendLine()
            appendLine("- Campaign ID: `$campaignId`")
            appendLine("- Final verdict: **${verdict(result)}**")
            appendLine("- Message: ${result.message}")
            appendLine()
            appendLine("## Baseline")
            appendFields(baseline, BASELINE_FIELDS)
            appendLine()
            appendLine("## SET_PATCH probe")
            appendLine("- Events: ${eventNames("bench_restart_settings_")}")
            appendLine()
            appendLine("## Restart candidate")
            appendLine("- Events: ${eventNames("bench_restart_transition_")}")
            appendLine()
            appendLine("## ACTIVATE")
            appendLine("- Events: ${eventNames("bench_restart_activate_")}")
            appendLine()
            appendLine("## Restart evidence")
            appendLine("- Device start before: ${value(before, "deviceReportedStartTime")}")
            appendLine("- Device start after: ${value(after, "deviceReportedStartTime")}")
            appendLine("- Age before: ${value(before, "deviceReportedPatchAge")}")
            appendLine("- Age immediately after: ${value(after, "deviceReportedPatchAge")}")
            appendLine("- Age second sample: ${value(subsequent, "deviceReportedPatchAge")}")
            appendLine("- Patch ID before/after: ${value(before, "patchId")} / ${value(after, "patchId")}")
            appendLine("- Sequence before/after: ${value(before, "currentSequence")} / ${value(after, "currentSequence")}")
            appendLine("- Reservoir before/after: ${value(before, "reservoir")} / ${value(after, "reservoir")}")
            appendLine()
            appendLine("## Command summary")
            appendLine("- TX events: ${events.count { it.name.endsWith("_tx") }}")
            appendLine("- PRIME TX: 0")
            appendLine("- STOP_PATCH TX: 0")
            appendLine()
            appendLine("## Final device state")
            appendLine("- ${(baseline + before + after + subsequent)["realPumpState"] ?: "UNKNOWN"}")
        }
    }

    private fun commandSummary(): JSONObject = JSONObject()
        .put("txEvents", events.count { it.name.endsWith("_tx") })
        .put("settingsTx", events.count { it.name in SETTINGS_WRITE_EVENTS })
        .put("candidateTx", events.count { it.name == "bench_restart_transition_tx" })
        .put("activateTx", events.count { it.name == "bench_restart_activate_tx" })
        .put("primeTx", 0)
        .put("stopPatchTx", 0)

    private fun restartEvidence(before: Map<String, Any?>, after: Map<String, Any?>, subsequent: Map<String, Any?>): JSONObject =
        JSONObject()
            .put("deviceStartBefore", encode(before["deviceReportedStartTime"]))
            .put("deviceStartAfter", encode(after["deviceReportedStartTime"]))
            .put("ageBefore", encode(before["deviceReportedPatchAge"]))
            .put("ageImmediatelyAfter", encode(after["deviceReportedPatchAge"]))
            .put("ageSecondSample", encode(subsequent["deviceReportedPatchAge"]))
            .put("patchIdBefore", encode(before["patchId"]))
            .put("patchIdAfter", encode(after["patchId"]))
            .put("sequenceBefore", encode(before["currentSequence"]))
            .put("sequenceAfter", encode(after["currentSequence"]))
            .put("reservoirBefore", encode(before["reservoir"]))
            .put("reservoirAfter", encode(after["reservoir"]))

    private fun eventGroup(prefix: String): JSONArray = eventArray { it.name.startsWith(prefix) }

    private fun eventArray(predicate: (BenchRestartEvent) -> Boolean): JSONArray = JSONArray().apply {
        events.filter(predicate).forEach { event ->
            put(
                JSONObject()
                    .put("state", event.state.name)
                    .put("name", event.name)
                    .put("fields", JSONObject().apply {
                        event.fields.forEach { (key, value) -> put(key, encode(value)) }
                    })
            )
        }
    }

    private fun fieldsOf(name: String): Map<String, Any?> = events.lastOrNull { it.name == name }?.fields.orEmpty()

    private fun eventNames(prefix: String): String =
        events.filter { it.name.startsWith(prefix) }.joinToString { it.name }.ifBlank { "none" }

    private fun StringBuilder.appendFields(fields: Map<String, Any?>, names: List<String>) {
        names.forEach { name -> appendLine("- $name: ${value(fields, name)}") }
    }

    private fun value(fields: Map<String, Any?>, name: String): Any = fields[name] ?: "UNKNOWN"

    private fun encode(value: Any?): Any = when (value) {
        null         -> JSONObject.NULL
        is ByteArray -> value.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        else         -> value
    }

    private fun verdict(result: BenchRestartResult): String = when {
        result.state == BenchRestartState.BLOCKED -> "BLOCKED"
        result.state == BenchRestartState.FAILED -> "FAILED"
        result.restartProven -> "CONFIRMED"
        else -> "NOT_CONFIRMED"
    }

    companion object {
        private val SETTINGS_WRITE_EVENTS = setOf("bench_restart_settings_probe_tx", "bench_restart_settings_restore_tx")
        private val BASELINE_FIELDS = listOf(
            "realPumpState",
            "firmware",
            "deviceType",
            "patchId",
            "deviceReportedStartTime",
            "deviceReportedPatchAge",
            "reservoir",
            "batteryA",
            "batteryB",
            "basalType",
            "basalRate",
            "currentSequence",
            "syncedSequence",
            "activeAlarms"
        )
    }
}

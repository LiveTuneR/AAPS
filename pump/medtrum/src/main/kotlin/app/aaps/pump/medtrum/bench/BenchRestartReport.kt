package app.aaps.pump.medtrum.bench

import org.json.JSONArray
import org.json.JSONObject

class BenchRestartReport(private val campaignId: String) {
    private val events = mutableListOf<BenchRestartEvent>()

    fun record(event: BenchRestartEvent) {
        events += event
    }

    fun toJson(result: BenchRestartResult): String = JSONObject()
        .put("schema", 2)
        .put("campaignId", campaignId)
        .put("finalVerdict", result.overallVerdict.name)
        .put("message", result.message)
        .put("restartProven", result.restartProven)
        .put("resultDimensions", resultDimensions(result))
        .put("snapshots", snapshotTableJson())
        .put("setPatchProbe", eventGroup("bench_real_set_patch_"))
        .put("activateProbe", eventGroup("bench_real_activate_"))
        .put("hiddenTransition", eventGroup("bench_restart_hidden_transition_"))
        .put("commandSummary", commandSummary(result))
        .put("errorsAndTimeouts", eventArray { event ->
            event.name.contains("failed") || event.name.contains("blocked") ||
                event.fields["timeout"] == true || event.fields["success"] == false
        })
        .put("campaignStateTransitions", eventArray { true })
        .put("finalDeviceState", finalDeviceState())
        .toString(2)

    fun toMarkdown(result: BenchRestartResult): String = buildString {
        appendLine("# Medtrum real BLE bench restart campaign")
        appendLine()
        appendLine("- Campaign ID: `$campaignId`")
        appendLine("- Known hardware probes: **${result.knownHardwareProbes}**")
        appendLine("- SET_PATCH while ACTIVE: **${result.setPatchWhileActive}**")
        appendLine("- SET_PATCH timer effect: **${result.setPatchTimerEffect}**")
        appendLine("- ACTIVATE while ACTIVE: **${result.activateWhileActive}**")
        appendLine("- ACTIVATE protocol result: `${result.activateResponseCode ?: "UNKNOWN"}`")
        appendLine("- ACTIVATE timer effect: **${result.activateTimerEffect}**")
        appendLine("- Hidden ACTIVE -> activation-ready transition: **${result.hiddenTransition}**")
        appendLine("- Overall experimental restart: **${result.overallVerdict}**")
        appendLine("- Message: ${result.message}")
        appendLine()
        appendLine("## Device timer snapshots")
        appendLine()
        appendLine("| Snapshot | state | deviceStart | deviceAge | patchId | reservoir |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        SNAPSHOT_EVENTS.forEach { (label, eventName) ->
            val fields = fieldsOf(eventName)
            appendLine(
                "| $label | ${value(fields, "realPumpState")} | ${timerValue(fields, "deviceReportedStartTime", "deviceReportedStartTimeAvailable")} | " +
                    "${timerValue(fields, "deviceReportedPatchAge", "deviceReportedPatchAgeAvailable")} | ${value(fields, "patchId")} | ${value(fields, "reservoir")} |"
            )
        }
        appendLine()
        appendLine("## Command outcomes")
        appendLine()
        appendLine("- Real BLE writes attempted: ${result.realBleWritesAttempted}")
        appendLine("- SET_PATCH: ${result.setPatchWrites}")
        appendLine("- ACTIVATE: ${result.activateWrites}")
        appendLine("- PRIME: 0")
        appendLine("- STOP_PATCH: 0")
        appendLine("- UNKNOWN/raw: 0")
        appendLine("- Automatic retries of bench writes: 0")
        appendLine()
        appendLine("## SET_PATCH")
        appendLine("- Events: ${eventNames("bench_real_set_patch_")}")
        appendLine("- Independent expiration readback: unavailable; acceptance and device telemetry are reported separately.")
        appendLine()
        appendLine("## ACTIVATE")
        appendLine("- Events: ${eventNames("bench_real_activate_")}")
        appendLine()
        appendLine("## Final device state")
        appendLine("- ${finalDeviceState()}")
    }

    private fun resultDimensions(result: BenchRestartResult): JSONObject = JSONObject()
        .put("knownHardwareProbes", result.knownHardwareProbes.name)
        .put("setPatchWhileActive", result.setPatchWhileActive.name)
        .put("setPatchTimerEffect", result.setPatchTimerEffect.name)
        .put("activateWhileActive", result.activateWhileActive.name)
        .put("activateResponseCode", result.activateResponseCode ?: JSONObject.NULL)
        .put("activateTimerEffect", result.activateTimerEffect.name)
        .put("hiddenTransition", result.hiddenTransition.name)
        .put("overallExperimentalRestart", result.overallVerdict.name)

    private fun commandSummary(result: BenchRestartResult): JSONObject = JSONObject()
        .put("realBleWritesAttempted", result.realBleWritesAttempted)
        .put("setPatch", result.setPatchWrites)
        .put("activate", result.activateWrites)
        .put("prime", 0)
        .put("stopPatch", 0)
        .put("unknownRaw", 0)
        .put("automaticBenchWriteRetries", 0)

    private fun snapshotTableJson(): JSONArray = JSONArray().apply {
        SNAPSHOT_EVENTS.forEach { (label, eventName) ->
            val fields = fieldsOf(eventName)
            if (fields.isNotEmpty()) {
                put(
                    JSONObject()
                        .put("snapshot", label)
                        .put("state", encode(fields["realPumpState"]))
                        .put("deviceStart", timerEncode(fields, "deviceReportedStartTime", "deviceReportedStartTimeAvailable"))
                        .put("deviceAge", timerEncode(fields, "deviceReportedPatchAge", "deviceReportedPatchAgeAvailable"))
                        .put("patchId", encode(fields["patchId"]))
                        .put("reservoir", encode(fields["reservoir"]))
                        .put("fields", JSONObject(fields.mapValues { encode(it.value) }))
                )
            }
        }
    }

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

    private fun value(fields: Map<String, Any?>, name: String): Any = fields[name] ?: "UNKNOWN"

    private fun timerValue(fields: Map<String, Any?>, valueName: String, availableName: String): Any =
        if (fields[availableName] == true) value(fields, valueName) else "NOT_EMITTED"

    private fun timerEncode(fields: Map<String, Any?>, valueName: String, availableName: String): Any =
        if (fields[availableName] == true) encode(fields[valueName]) else JSONObject.NULL

    private fun encode(value: Any?): Any = when (value) {
        null         -> JSONObject.NULL
        is ByteArray -> value.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        else         -> value
    }

    private fun finalDeviceState(): Any = SNAPSHOT_EVENTS
        .asReversed()
        .firstNotNullOfOrNull { (_, name) -> fieldsOf(name)["realPumpState"] }
        ?: "UNKNOWN"

    companion object {
        private val SNAPSHOT_EVENTS = listOf(
            "A0" to "bench_restart_snapshot_a0",
            "B1" to "bench_real_set_patch_idempotent_snapshot",
            "B2" to "bench_real_set_patch_toggle_snapshot",
            "B3" to "bench_real_set_patch_restore_snapshot",
            "PRE-ACTIVATE" to "bench_restart_pre_activate_snapshot",
            "C1" to "bench_real_activate_snapshot_c1",
            "C2" to "bench_real_activate_snapshot_c2"
        )
    }
}
